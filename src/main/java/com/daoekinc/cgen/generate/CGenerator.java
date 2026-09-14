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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CGenerator {
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
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
        return generate(project, scope, false, (completed, total, path) -> { });
    }

    public List<Path> generate(ProjectConfig project, Path scope, ProgressListener progress) {
        return generate(project, scope, false, progress);
    }

    public List<Path> generate(ProjectConfig project, Path scope, boolean force, ProgressListener progress) {
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
            UserRegions regions = tags.readForGeneration(output, force);
            outputs.add(new Output(output, interfaceRenderer.render(project, spec, documentation, regions)));
        }
        for (ModulePlan plan : modules) {
            ModuleSpec module = plan.module();
            Path header = module.source().getParent().resolve(module.header());
            Path source = module.source().getParent().resolve(module.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            removeGeneratedFunctionDefaults(project, interfaces.values(), sourceRegions);
            outputs.add(new Output(header, moduleRenderer.renderHeader(project, module, plan.interfaces(), documentation, headerRegions)));
            outputs.add(new Output(source, moduleRenderer.renderSource(project, module, plan.interfaces(), documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".state-machine.yaml", project)) {
            StateMachineSpec machine = StateMachineSpec.from(path, yamlFiles.load(path));
            Path header = machine.source().getParent().resolve(machine.header());
            Path source = machine.source().getParent().resolve(machine.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            outputs.add(new Output(header, stateMachineRenderer.renderHeader(project, machine, documentation, headerRegions)));
            outputs.add(new Output(source, stateMachineRenderer.renderSource(project, machine, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".status-codes.yaml", project)) {
            StatusCodesSpec status = StatusCodesSpec.from(path, yamlFiles.load(path));
            Path output = path.getParent().resolve(status.header()).toAbsolutePath().normalize();
            requireUniqueDestination(destinations, output);
            UserRegions regions = tags.readForGeneration(output, force);
            outputs.add(new Output(output, statusCodesRenderer.render(project, status, documentation, regions)));
        }
        for (Path path : specificationFiles(scope, ".observer.yaml", project)) {
            ObserverSpec observer = ObserverSpec.from(path, yamlFiles.load(path));
            InterfaceSpec listener = resolveInterface(interfaces, observer.interfaceName(), path, "interface");
            requireVoidFunctions(listener, path, "observer listener");
            Path header = observer.source().getParent().resolve(observer.header());
            Path source = observer.source().getParent().resolve(observer.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            outputs.add(new Output(header, observerRenderer.renderHeader(project, observer, listener, documentation, headerRegions)));
            outputs.add(new Output(source, observerRenderer.renderSource(project, observer, listener, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".command-table.yaml", project)) {
            CommandTableSpec table = CommandTableSpec.from(path, yamlFiles.load(path));
            Path header = table.source().getParent().resolve(table.header());
            Path source = table.source().getParent().resolve(table.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            outputs.add(new Output(header, commandTableRenderer.renderHeader(project, table, documentation, headerRegions)));
            outputs.add(new Output(source, commandTableRenderer.renderSource(project, table, documentation, sourceRegions)));
        }
        for (Path path : specificationFiles(scope, ".adapter.yaml", project)) {
            AdapterSpec adapter = AdapterSpec.from(path, yamlFiles.load(path));
            InterfaceSpec from = resolveInterface(interfaces, adapter.from(), path, "from");
            InterfaceSpec to = resolveInterface(interfaces, adapter.to(), path, "to");
            Map<String, String> mappings = resolveAdapterMappings(adapter, from, to, path);
            Path header = adapter.source().getParent().resolve(adapter.header());
            Path source = adapter.source().getParent().resolve(adapter.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            removeGeneratedFunctionDefaults(project, List.of(from), sourceRegions);
            outputs.add(new Output(header, adapterRenderer.renderHeader(project, adapter, from, to, documentation, headerRegions)));
            outputs.add(new Output(source, adapterRenderer.renderSource(project, adapter, from, to, mappings, documentation, sourceRegions)));
        }

        int total = outputs.size();
        int[] completed = {0};
        for (Output output : outputs) {
            tags.writeGenerated(output.path(), output.content(), project.lineEnding());
            completed[0]++;
            progress.onFileGenerated(completed[0], total, output.path());
        }
        return outputs.stream().map(Output::path).toList();
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onFileGenerated(int completed, int total, Path path);
    }

    public List<Path> cleanTags(ProjectConfig project, Path scope) {
        List<Path> cleaned = new ArrayList<>();
        for (InterfaceSpec spec : loadInterfaces(project).values()) {
            if (spec.source().startsWith(scope)) {
                cleanOutputs(spec.source().getParent(), List.of(spec.header()), cleaned);
            }
        }
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(module.header(), module.sourceFile()), cleaned);
        }
        for (Path path : specificationFiles(scope, ".state-machine.yaml", project)) {
            StateMachineSpec machine = StateMachineSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(machine.header(), machine.sourceFile()), cleaned);
        }
        for (Path path : specificationFiles(scope, ".status-codes.yaml", project)) {
            StatusCodesSpec status = StatusCodesSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(status.header()), cleaned);
        }
        for (Path path : specificationFiles(scope, ".observer.yaml", project)) {
            ObserverSpec observer = ObserverSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(observer.header(), observer.sourceFile()), cleaned);
        }
        for (Path path : specificationFiles(scope, ".command-table.yaml", project)) {
            CommandTableSpec table = CommandTableSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(table.header(), table.sourceFile()), cleaned);
        }
        for (Path path : specificationFiles(scope, ".adapter.yaml", project)) {
            AdapterSpec adapter = AdapterSpec.from(path, yamlFiles.load(path));
            cleanOutputs(path.getParent(), List.of(adapter.header(), adapter.sourceFile()), cleaned);
        }
        return List.copyOf(cleaned);
    }

    private void cleanOutputs(Path specDirectory, List<String> outputFileNames, List<Path> cleaned) {
        for (String fileName : outputFileNames) {
            Path output = specDirectory.resolve(fileName);
            if (Files.isRegularFile(output) && tags.stripTags(output)) {
                cleaned.add(output);
            }
        }
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

    /**
     * Renames a module: updates its {@code name}/{@code header}/{@code source} in the YAML
     * spec, moves the spec and any already-generated header/source files to their new names
     * (so the moved files still carry the old CGen markers), then regenerates. Because
     * regeneration extracts user regions from whatever already sits at the destination path,
     * the move-then-regenerate order is what carries user code forward instead of starting
     * the renamed files empty.
     */
    public RenameResult renameModule(ProjectConfig project, String oldIdentifier, String newName) {
        if (!C_IDENTIFIER.matcher(newName).matches()) {
            throw new CGenException("New module name must be a valid C identifier, got '" + newName + "'");
        }
        Path specPath = resolveModuleSpecPath(project, oldIdentifier);
        ModuleSpec module = ModuleSpec.from(specPath, yamlFiles.load(specPath));
        String oldName = module.name();
        if (oldName.equals(newName)) {
            throw new CGenException("Module '" + oldName + "' is already named '" + newName + "'");
        }
        Path directory = specPath.getParent();

        String newHeader = module.header().equals(oldName + ".h") ? newName + ".h" : module.header();
        String newSource = module.sourceFile().equals(oldName + ".c") ? newName + ".c" : module.sourceFile();
        String newSpecFileName = specPath.getFileName().toString().equals(oldName + ".module.yaml")
                ? newName + ".module.yaml" : specPath.getFileName().toString();

        Path oldHeaderPath = directory.resolve(module.header());
        Path newHeaderPath = directory.resolve(newHeader);
        Path oldSourcePath = directory.resolve(module.sourceFile());
        Path newSourcePath = directory.resolve(newSource);
        Path newSpecPath = directory.resolve(newSpecFileName);

        requireRenameTarget(newSpecPath, specPath);
        requireRenameTarget(newHeaderPath, oldHeaderPath);
        requireRenameTarget(newSourcePath, oldSourcePath);

        String content = readText(specPath);
        content = replaceScalarField(content, "name", oldName, newName);
        if (!newHeader.equals(module.header())) {
            content = replaceScalarField(content, "header", module.header(), newHeader);
        }
        if (!newSource.equals(module.sourceFile())) {
            content = replaceScalarField(content, "source", module.sourceFile(), newSource);
        }

        List<Move> moved = new ArrayList<>();
        moveIfExists(specPath, newSpecPath, moved);
        moveIfExists(oldHeaderPath, newHeaderPath, moved);
        moveIfExists(oldSourcePath, newSourcePath, moved);
        writeText(newSpecPath, content);

        return new RenameResult(newSpecPath, oldName, newName, List.copyOf(moved));
    }

    private Path resolveModuleSpecPath(ProjectConfig project, String moduleName) {
        List<Path> matches = new ArrayList<>();
        for (Path path : specificationFiles(project.root(), ".module.yaml", project)) {
            ModuleSpec candidate = ModuleSpec.from(path, yamlFiles.load(path));
            if (candidate.name().equals(moduleName)) {
                matches.add(path);
            }
        }
        if (matches.isEmpty()) {
            throw new CGenException("No module named '" + moduleName + "' found in this project");
        }
        if (matches.size() > 1) {
            throw new CGenException("Multiple modules named '" + moduleName + "': " + matches);
        }
        return matches.get(0);
    }

    private static void requireRenameTarget(Path target, Path current) {
        if (!target.equals(current) && Files.exists(target)) {
            throw new CGenException("Cannot rename: " + target + " already exists");
        }
    }

    private static void moveIfExists(Path from, Path to, List<Move> moved) {
        if (from.equals(to) || !Files.exists(from)) {
            return;
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            moved.add(new Move(from, to));
        } catch (IOException exception) {
            throw new CGenException("Cannot rename " + from + " to " + to + ": " + exception.getMessage(), exception);
        }
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new CGenException("Cannot read " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static void writeText(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CGenException("Cannot write " + path + ": " + exception.getMessage(), exception);
        }
    }

    // Matches an unindented "key: value" line, tolerating a surrounding quote and a trailing
    // comment, so the rename touches only the top-level scalar and not a same-named nested key.
    private static String replaceScalarField(String content, String key, String oldValue, String newValue) {
        Pattern pattern = Pattern.compile("(?m)^(" + Pattern.quote(key) + ":\\s*)(['\"]?)"
                + Pattern.quote(oldValue) + "\\2(\\s*(?:#.*)?)$");
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            throw new CGenException("Cannot update '" + key + "' in the module spec; expected '" + oldValue
                    + "' on its own '" + key + ":' line");
        }
        return matcher.replaceFirst("$1$2" + Matcher.quoteReplacement(newValue) + "$3");
    }

    public record Move(Path from, Path to) {
    }

    public record RenameResult(Path specPath, String oldName, String newName, List<Move> movedFiles) {
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
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isExcludedProjectPath(project.root(), dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // A nested cgen.yaml marks the start of a separate, self-contained project
                    // (e.g. an imported library) - its files are that project's to generate,
                    // with its own rules, not this scan's.
                    if (!dir.equals(directory) && Files.isRegularFile(dir.resolve(ProjectService.PROJECT_FILE))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(suffix)) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new CGenException("Cannot scan " + directory + ": " + exception.getMessage(), exception);
        }
        return found.stream().sorted(Comparator.comparing(Path::toString)).toList();
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
