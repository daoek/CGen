package com.daoekinc.cgen.generate;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ModuleSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.project.ProjectService;
import com.daoekinc.cgen.tag.TagHelper;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CGenerator {
    private final YamlFiles yamlFiles;
    private final TagHelper tags;
    private final ProjectService projects;
    private final InterfaceRenderer interfaceRenderer = new InterfaceRenderer();
    private final ModuleRenderer moduleRenderer = new ModuleRenderer();

    public CGenerator(YamlFiles yamlFiles, TagHelper tags, ProjectService projects) {
        this.yamlFiles = yamlFiles;
        this.tags = tags;
        this.projects = projects;
    }

    public List<Path> generate(ProjectConfig project, Path scope) {
        DocumentationRenderer documentation = new DocumentationRenderer(project, yamlFiles);
        Map<String, InterfaceSpec> interfaces = loadInterfaces(project);
        List<ModulePlan> modules = new ArrayList<>();
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            List<InterfaceSpec> implemented = new ArrayList<>();
            for (String name : module.implementsInterfaces()) {
                InterfaceSpec contract = interfaces.get(name);
                if (contract == null) {
                    throw new CGenException(path + " implements unknown interface '" + name + "'");
                }
                implemented.add(contract);
            }
            modules.add(new ModulePlan(module, List.copyOf(implemented)));
        }

        // Preflight every destination and render every file before changing the filesystem.
        List<Output> outputs = new ArrayList<>();
        Set<Path> destinations = new LinkedHashSet<>();
        for (InterfaceSpec spec : interfaces.values()) {
            if (!spec.source().startsWith(scope)) {
                continue;
            }
            Path output = spec.source().getParent().resolve(spec.header()).toAbsolutePath().normalize();
            requireUniqueDestination(destinations, output);
            UserRegions regions = tags.readForGeneration(output);
            outputs.add(new Output(output, interfaceRenderer.render(project, spec, documentation, regions)));
        }
        for (ModulePlan plan : modules) {
            ModuleSpec module = plan.module();
            Path header = module.source().getParent().resolve(module.header());
            Path source = module.source().getParent().resolve(module.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            removeGeneratedFunctionDefaults(project, interfaces.values(), sourceRegions);
            outputs.add(new Output(header, moduleRenderer.renderHeader(project, module, plan.interfaces(), documentation, headerRegions)));
            outputs.add(new Output(source, moduleRenderer.renderSource(project, module, plan.interfaces(), documentation, sourceRegions)));
        }
        outputs.forEach(output -> tags.writeGenerated(output.path(), output.content(), project.lineEnding()));
        return outputs.stream().map(Output::path).toList();
    }

    public List<Path> cleanTags(ProjectConfig project, Path scope) {
        List<Path> cleaned = new ArrayList<>();
        for (InterfaceSpec spec : loadInterfaces(project).values()) {
            if (spec.source().startsWith(scope)) {
                Path header = spec.source().getParent().resolve(spec.header());
                if (Files.isRegularFile(header) && tags.stripTags(header)) {
                    cleaned.add(header);
                }
            }
        }
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            for (Path output : List.of(path.getParent().resolve(module.header()), path.getParent().resolve(module.sourceFile()))) {
                if (Files.isRegularFile(output) && tags.stripTags(output)) {
                    cleaned.add(output);
                }
            }
        }
        return List.copyOf(cleaned);
    }

    public DetachResult detach(ProjectConfig project) {
        Set<Path> configurationFiles = new LinkedHashSet<>();
        configurationFiles.addAll(specificationFiles(project.root(), ".interface.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".module.yaml", project));
        if (project.documentation().customFile() != null) {
            configurationFiles.add(project.documentation().customFile());
        }
        configurationFiles.add(project.root().resolve(ProjectService.PROJECT_FILE));

        List<Path> cleaned = cleanTags(project, project.root());
        List<Path> deleted = new ArrayList<>();
        for (Path file : configurationFiles) {
            try {
                if (Files.deleteIfExists(file)) {
                    deleted.add(file);
                }
            } catch (IOException exception) {
                throw new CGenException("Cannot remove CGen configuration " + file + ": " + exception.getMessage(), exception);
            }
        }
        return new DetachResult(List.copyOf(cleaned), List.copyOf(deleted));
    }

    private Map<String, InterfaceSpec> loadInterfaces(ProjectConfig project) {
        Map<String, InterfaceSpec> result = new LinkedHashMap<>();
        for (Path path : specificationFiles(project.root(), ".interface.yaml", project)) {
            InterfaceSpec spec = InterfaceSpec.from(path, yamlFiles.load(path));
            InterfaceSpec previous = result.putIfAbsent(spec.name(), spec);
            if (previous != null) {
                throw new CGenException("Duplicate interface name '" + spec.name() + "' in " + previous.source() + " and " + path);
            }
        }
        return result;
    }

    private List<Path> specificationFiles(Path directory, String suffix, ProjectConfig project) {
        projects.existingDirectory(project, directory);
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !isExcludedProjectPath(project.root(), path))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new CGenException("Cannot scan " + directory + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean isExcludedProjectPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        return first.equals(".git") || first.equals("target");
    }

    private static void requireUniqueDestination(Set<Path> destinations, Path path) {
        if (!destinations.add(path)) {
            throw new CGenException("Multiple YAML specifications generate " + path);
        }
    }

    private static void removeGeneratedFunctionDefaults(ProjectConfig project, Iterable<InterfaceSpec> interfaces,
                                                        UserRegions regions) {
        for (InterfaceSpec contract : interfaces) {
            for (InterfaceSpec.Function function : contract.functions()) {
                if (!function.returnType().equals("void")) {
                    String name = "function." + contract.name() + "." + function.name() + ".body";
                    String oldDefault = RenderSupport.indent(project, 1) + "return " + function.invalidReturn() + ";";
                    regions.removeIfMatches(name, oldDefault);
                }
            }
        }
    }

    private record ModulePlan(ModuleSpec module, List<InterfaceSpec> interfaces) {
    }

    public record DetachResult(List<Path> cleanedFiles, List<Path> deletedConfigurationFiles) {
    }

    private record Output(Path path, String content) {
    }
}
