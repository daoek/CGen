package com.daoekinc.cgen.generate;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.model.AdapterSpec;
import com.daoekinc.cgen.model.CommandTableSpec;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ModuleSpec;
import com.daoekinc.cgen.model.ObserverSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.model.StateMachineSpec;
import com.daoekinc.cgen.model.StatusCodesSpec;
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
    private final StateMachineRenderer stateMachineRenderer = new StateMachineRenderer();
    private final ObserverRenderer observerRenderer = new ObserverRenderer();
    private final CommandTableRenderer commandTableRenderer = new CommandTableRenderer();
    private final StatusCodesRenderer statusCodesRenderer = new StatusCodesRenderer();
    private final AdapterRenderer adapterRenderer = new AdapterRenderer();

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
        for (Path path : specificationFiles(scope, ".state-machine.yaml", project)) {
            StateMachineSpec machine = StateMachineSpec.from(path, yamlFiles.load(path));
            Path directory = machine.source().getParent().resolve(machine.name());
            Path header = directory.resolve(machine.header());
            Path source = directory.resolve(machine.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            outputs.add(new Output(header, stateMachineRenderer.renderHeader(project, machine, documentation, headerRegions)));
            outputs.add(new Output(source, stateMachineRenderer.renderSource(project, machine, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".status-codes.yaml", project)) {
            StatusCodesSpec status = StatusCodesSpec.from(path, yamlFiles.load(path));
            Path output = path.getParent().resolve(status.header()).toAbsolutePath().normalize();
            requireUniqueDestination(destinations, output);
            UserRegions regions = tags.readForGeneration(output);
            outputs.add(new Output(output, statusCodesRenderer.render(project, status, documentation, regions)));
        }
        for (Path path : specificationFiles(scope, ".observer.yaml", project)) {
            ObserverSpec observer = ObserverSpec.from(path, yamlFiles.load(path));
            InterfaceSpec listener = resolveInterface(interfaces, observer.interfaceName(), path, "interface");
            requireVoidFunctions(listener, path, "observer listener");
            Path directory = observer.source().getParent().resolve(observer.name());
            Path header = directory.resolve(observer.header());
            Path source = directory.resolve(observer.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            outputs.add(new Output(header, observerRenderer.renderHeader(project, observer, listener, documentation, headerRegions)));
            outputs.add(new Output(source, observerRenderer.renderSource(project, observer, listener, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".command-table.yaml", project)) {
            CommandTableSpec table = CommandTableSpec.from(path, yamlFiles.load(path));
            Path directory = table.source().getParent().resolve(table.name());
            Path header = directory.resolve(table.header());
            Path source = directory.resolve(table.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            outputs.add(new Output(header, commandTableRenderer.renderHeader(project, table, documentation, headerRegions)));
            outputs.add(new Output(source, commandTableRenderer.renderSource(project, table, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".adapter.yaml", project)) {
            AdapterSpec adapter = AdapterSpec.from(path, yamlFiles.load(path));
            InterfaceSpec from = resolveInterface(interfaces, adapter.from(), path, "from");
            InterfaceSpec to = resolveInterface(interfaces, adapter.to(), path, "to");
            Map<String, String> mappings = resolveAdapterMappings(adapter, from, to, path);
            Path directory = adapter.source().getParent().resolve(adapter.name());
            Path header = directory.resolve(adapter.header());
            Path source = directory.resolve(adapter.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header);
            UserRegions sourceRegions = tags.readForGeneration(source);
            removeGeneratedFunctionDefaults(project, List.of(from), sourceRegions);
            outputs.add(new Output(header, adapterRenderer.renderHeader(project, adapter, from, to, documentation, headerRegions)));
            outputs.add(new Output(source, adapterRenderer.renderSource(project, adapter, from, to, mappings, documentation, sourceRegions)));
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
        for (Path path : specificationFiles(scope, ".state-machine.yaml", project)) {
            StateMachineSpec machine = StateMachineSpec.from(path, yamlFiles.load(path));
            Path directory = path.getParent().resolve(machine.name());
            for (Path output : List.of(directory.resolve(machine.header()), directory.resolve(machine.sourceFile()))) {
                if (Files.isRegularFile(output) && tags.stripTags(output)) {
                    cleaned.add(output);
                }
            }
        }
        for (Path path : specificationFiles(scope, ".status-codes.yaml", project)) {
            StatusCodesSpec status = StatusCodesSpec.from(path, yamlFiles.load(path));
            Path header = path.getParent().resolve(status.header());
            if (Files.isRegularFile(header) && tags.stripTags(header)) {
                cleaned.add(header);
            }
        }
        for (Path path : specificationFiles(scope, ".observer.yaml", project)) {
            ObserverSpec observer = ObserverSpec.from(path, yamlFiles.load(path));
            Path directory = path.getParent().resolve(observer.name());
            for (Path output : List.of(directory.resolve(observer.header()), directory.resolve(observer.sourceFile()))) {
                if (Files.isRegularFile(output) && tags.stripTags(output)) {
                    cleaned.add(output);
                }
            }
        }
        for (Path path : specificationFiles(scope, ".command-table.yaml", project)) {
            CommandTableSpec table = CommandTableSpec.from(path, yamlFiles.load(path));
            Path directory = path.getParent().resolve(table.name());
            for (Path output : List.of(directory.resolve(table.header()), directory.resolve(table.sourceFile()))) {
                if (Files.isRegularFile(output) && tags.stripTags(output)) {
                    cleaned.add(output);
                }
            }
        }
        for (Path path : specificationFiles(scope, ".adapter.yaml", project)) {
            AdapterSpec adapter = AdapterSpec.from(path, yamlFiles.load(path));
            Path directory = path.getParent().resolve(adapter.name());
            for (Path output : List.of(directory.resolve(adapter.header()), directory.resolve(adapter.sourceFile()))) {
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
        configurationFiles.addAll(specificationFiles(project.root(), ".state-machine.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".status-codes.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".observer.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".command-table.yaml", project));
        configurationFiles.addAll(specificationFiles(project.root(), ".adapter.yaml", project));
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

    private static InterfaceSpec resolveInterface(Map<String, InterfaceSpec> interfaces, String name, Path path, String label) {
        InterfaceSpec contract = interfaces.get(name);
        if (contract == null) {
            throw new CGenException(path + " references unknown " + label + " interface '" + name + "'");
        }
        return contract;
    }

    private static void requireVoidFunctions(InterfaceSpec contract, Path path, String label) {
        for (InterfaceSpec.Function function : contract.functions()) {
            if (!function.returnType().equals("void")) {
                throw new CGenException(path + ": " + label + " interface '" + contract.name()
                        + "' function '" + function.name() + "' must return void");
            }
        }
    }

    private static Map<String, String> resolveAdapterMappings(AdapterSpec adapter, InterfaceSpec from, InterfaceSpec to, Path path) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (AdapterSpec.Mapping mapping : adapter.mappings()) {
            InterfaceSpec.Function fromFunction = from.functions().stream()
                    .filter(function -> function.name().equals(mapping.from())).findFirst()
                    .orElseThrow(() -> new CGenException(path + " maps unknown function '" + mapping.from()
                            + "' on interface '" + from.name() + "'"));
            InterfaceSpec.Function toFunction = to.functions().stream()
                    .filter(function -> function.name().equals(mapping.to())).findFirst()
                    .orElseThrow(() -> new CGenException(path + " maps unknown function '" + mapping.to()
                            + "' on interface '" + to.name() + "'"));
            if (!fromFunction.returnType().equals(toFunction.returnType())) {
                throw new CGenException(path + " mapping '" + mapping.from() + "' -> '" + mapping.to()
                        + "' has mismatched return types ('" + fromFunction.returnType() + "' vs '" + toFunction.returnType() + "')");
            }
            List<String> fromTypes = fromFunction.parameters().stream().map(InterfaceSpec.Parameter::type).toList();
            List<String> toTypes = toFunction.parameters().stream().map(InterfaceSpec.Parameter::type).toList();
            if (!fromTypes.equals(toTypes)) {
                throw new CGenException(path + " mapping '" + mapping.from() + "' -> '" + mapping.to()
                        + "' has mismatched parameter types; map functions with identical signatures, or leave '"
                        + mapping.from() + "' unmapped and implement it by hand");
            }
            mappings.put(mapping.from(), mapping.to());
        }
        return mappings;
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
