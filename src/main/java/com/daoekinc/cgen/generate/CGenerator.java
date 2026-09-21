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
import com.daoekinc.cgen.statesmith.StateSmithRunner;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.SwitchTagProcessor;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CGenerator {
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final YamlFiles yamlFiles;
    private final TagHelper tags;
    private final ProjectService projects;
    private final InterfaceRenderer interfaceRenderer = new InterfaceRenderer();
    private final ModuleRenderer moduleRenderer = new ModuleRenderer();
    private final StateMachineRenderer stateMachineRenderer = new StateMachineRenderer();
    private final StateSmithPlantUmlRenderer stateSmithPlantUmlRenderer = new StateSmithPlantUmlRenderer();
    private final StateSmithHooksRenderer stateSmithHooksRenderer = new StateSmithHooksRenderer();
    private final StateSmithApiRenderer stateSmithApiRenderer = new StateSmithApiRenderer();
    private final StateSmithRunner stateSmithRunner = new StateSmithRunner();
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
        return generate(project, scope, false, (completed, total, specSource, outputPath, existed, regionsCarried) -> { });
    }

    public List<Path> generate(ProjectConfig project, Path scope, ProgressListener progress) {
        return generate(project, scope, false, progress);
    }

    public List<Path> generate(ProjectConfig project, Path scope, boolean force, ProgressListener progress) {
        return generate(project, scope, force, progress, (enumType, sourceFile, members) -> false);
    }

    public List<Path> generate(ProjectConfig project, Path scope, boolean force, ProgressListener progress,
                               SwitchEnumConfirmation switchEnumConfirmation) {
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
            boolean existed = Files.exists(output);
            UserRegions regions = tags.readForGeneration(output, force);
            outputs.add(new Output(spec.source(), output, interfaceRenderer.render(project, spec, documentation, regions),
                    existed, regions.regionCount()));
        }
        for (ModulePlan plan : modules) {
            ModuleSpec module = plan.module();
            Path header = module.source().getParent().resolve(module.header());
            Path source = module.source().getParent().resolve(module.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            boolean headerExisted = Files.exists(header);
            boolean sourceExisted = Files.exists(source);
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            removeGeneratedFunctionDefaults(project, interfaces.values(), sourceRegions);
            outputs.add(new Output(module.source(), header, moduleRenderer.renderHeader(project, module, plan.interfaces(), documentation, headerRegions),
                    headerExisted, headerRegions.regionCount()));
            outputs.add(new Output(module.source(), source, moduleRenderer.renderSource(project, module, plan.interfaces(), documentation, sourceRegions),
                    sourceExisted, sourceRegions.regionCount()));
        }
        List<StatesmithRun> statesmithRuns = new ArrayList<>();
        for (Path path : specificationFiles(scope, ".state-machine.yaml", project)) {
            StateMachineSpec machine = StateMachineSpec.from(path, yamlFiles.load(path));
            Path header = machine.source().getParent().resolve(machine.header());
            Path source = machine.source().getParent().resolve(machine.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            boolean headerExisted = Files.exists(header);
            boolean sourceExisted = Files.exists(source);
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            if (machine.engine() == StateMachineSpec.Engine.BUILTIN) {
                outputs.add(new Output(machine.source(), header, stateMachineRenderer.renderHeader(project, machine, documentation, headerRegions),
                        headerExisted, headerRegions.regionCount()));
                outputs.add(new Output(machine.source(), source, stateMachineRenderer.renderSource(project, machine, documentation, sourceRegions),
                        sourceExisted, sourceRegions.regionCount()));
            } else {
                Path directory = machine.source().getParent();
                Path hooksHeader = directory.resolve(StateSmithNaming.hooksHeaderFileName(machine.name()));
                Path hooksSource = directory.resolve(StateSmithNaming.hooksSourceFileName(machine.name()));
                Path smDirectory = directory.resolve(StateSmithNaming.smDirectoryName(machine.name()));
                Path plantuml = smDirectory.resolve(StateSmithNaming.plantUmlFileName(machine.name()));
                Path smHeader = smDirectory.resolve(StateSmithNaming.smHeaderFileName(machine.name()));
                Path smSource = smDirectory.resolve(StateSmithNaming.smSourceFileName(machine.name()));
                requireUniqueDestination(destinations, hooksHeader.toAbsolutePath().normalize());
                requireUniqueDestination(destinations, hooksSource.toAbsolutePath().normalize());
                requireUniqueDestination(destinations, plantuml.toAbsolutePath().normalize());

                UserRegions hooksHeaderRegions = tags.readForGeneration(hooksHeader, force);
                UserRegions hooksSourceRegions = tags.readForGeneration(hooksSource, force);
                UserRegions plantumlRegions = tags.readForGeneration(plantuml, force);

                outputs.add(new Output(machine.source(), plantuml, stateSmithPlantUmlRenderer.render(project, machine),
                        Files.exists(plantuml), plantumlRegions.regionCount()));
                outputs.add(new Output(machine.source(), hooksHeader, stateSmithHooksRenderer.renderHeader(project, machine, documentation, hooksHeaderRegions),
                        Files.exists(hooksHeader), hooksHeaderRegions.regionCount()));
                outputs.add(new Output(machine.source(), hooksSource, stateSmithHooksRenderer.renderSource(project, machine, documentation, hooksSourceRegions),
                        Files.exists(hooksSource), hooksSourceRegions.regionCount()));
                outputs.add(new Output(machine.source(), header, stateSmithApiRenderer.renderHeader(project, machine, documentation, headerRegions),
                        headerExisted, headerRegions.regionCount()));
                outputs.add(new Output(machine.source(), source, stateSmithApiRenderer.renderSource(project, machine, documentation, sourceRegions),
                        sourceExisted, sourceRegions.regionCount()));
                statesmithRuns.add(new StatesmithRun(machine.source(), plantuml, smHeader, smSource));
            }
        }
        for (Path path : specificationFiles(scope, ".status-codes.yaml", project)) {
            StatusCodesSpec status = StatusCodesSpec.from(path, yamlFiles.load(path));
            Path output = path.getParent().resolve(status.header()).toAbsolutePath().normalize();
            requireUniqueDestination(destinations, output);
            boolean existed = Files.exists(output);
            UserRegions regions = tags.readForGeneration(output, force);
            outputs.add(new Output(status.source(), output, statusCodesRenderer.render(project, status, documentation, regions),
                    existed, regions.regionCount()));
        }
        for (Path path : specificationFiles(scope, ".observer.yaml", project)) {
            ObserverSpec observer = ObserverSpec.from(path, yamlFiles.load(path));
            InterfaceSpec listener = resolveInterface(interfaces, observer.interfaceName(), path, "interface");
            requireVoidFunctions(listener, path, "observer listener");
            Path header = observer.source().getParent().resolve(observer.header());
            Path source = observer.source().getParent().resolve(observer.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            boolean headerExisted = Files.exists(header);
            boolean sourceExisted = Files.exists(source);
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            outputs.add(new Output(observer.source(), header, observerRenderer.renderHeader(project, observer, listener, documentation, headerRegions),
                    headerExisted, headerRegions.regionCount()));
            outputs.add(new Output(observer.source(), source, observerRenderer.renderSource(project, observer, listener, documentation, sourceRegions),
                    sourceExisted, sourceRegions.regionCount()));
        }
        for (Path path : specificationFiles(scope, ".command-table.yaml", project)) {
            CommandTableSpec table = CommandTableSpec.from(path, yamlFiles.load(path));
            Path header = table.source().getParent().resolve(table.header());
            Path source = table.source().getParent().resolve(table.sourceFile());
            requireUniqueDestination(destinations, header.toAbsolutePath().normalize());
            requireUniqueDestination(destinations, source.toAbsolutePath().normalize());
            boolean headerExisted = Files.exists(header);
            boolean sourceExisted = Files.exists(source);
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            outputs.add(new Output(table.source(), header, commandTableRenderer.renderHeader(project, table, documentation, headerRegions),
                    headerExisted, headerRegions.regionCount()));
            outputs.add(new Output(table.source(), source, commandTableRenderer.renderSource(project, table, documentation, sourceRegions),
                    sourceExisted, sourceRegions.regionCount()));
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
            boolean headerExisted = Files.exists(header);
            boolean sourceExisted = Files.exists(source);
            UserRegions headerRegions = tags.readForGeneration(header, force);
            UserRegions sourceRegions = tags.readForGeneration(source, force);
            removeGeneratedFunctionDefaults(project, List.of(from), sourceRegions);
            outputs.add(new Output(adapter.source(), header, adapterRenderer.renderHeader(project, adapter, from, to, documentation, headerRegions),
                    headerExisted, headerRegions.regionCount()));
            outputs.add(new Output(adapter.source(), source, adapterRenderer.renderSource(project, adapter, from, to, mappings, documentation, sourceRegions),
                    sourceExisted, sourceRegions.regionCount()));
        }

        boolean anySwitchTags = outputs.stream().anyMatch(output -> SwitchTagProcessor.isUsed(output.content()));
        EnumIndex enumIndex = anySwitchTags ? buildEnumIndex(project) : null;
        Map<Path, List<String>> linkedFileMembers = new LinkedHashMap<>();
        Map<String, ExternalEnumMatch> resolvedExternal = new LinkedHashMap<>();
        Map<Path, Set<String>> linkedThisRun = new LinkedHashMap<>();

        int total = outputs.size();
        int[] completed = {0};
        for (Output output : outputs) {
            String content = output.content();
            if (enumIndex != null && SwitchTagProcessor.isUsed(content)) {
                Function<String, List<String>> resolver = enumType -> resolveSwitchEnum(project, enumType, output.specSource(),
                        enumIndex, linkedFileMembers, resolvedExternal, linkedThisRun, switchEnumConfirmation);
                content = SwitchTagProcessor.process(content, output.path(), project.indent(), resolver);
            }
            tags.writeGenerated(output.path(), content, project.lineEnding());
            completed[0]++;
            progress.onFileGenerated(completed[0], total, output.specSource(), output.path(), output.existed(), output.regionsCarried());
        }

        List<Path> generatedFiles = new ArrayList<>(outputs.stream().map(Output::path).toList());
        if (!statesmithRuns.isEmpty()) {
            stateSmithRunner.checkVersion(project);
            for (StatesmithRun run : statesmithRuns) {
                stateSmithRunner.run(run.plantuml(), run.yamlSource(), project);
                if (!hasGeneratedMarker(run.smHeader()) || !hasGeneratedMarker(run.smSource())) {
                    throw new CGenException("StateSmith did not produce " + run.smHeader() + " and " + run.smSource()
                            + " with CGen's file marker for " + run.yamlSource()
                            + " - check the generated .plantuml's $CONFIG [RenderConfig] FileTop setting");
                }
                generatedFiles.add(run.smHeader());
                generatedFiles.add(run.smSource());
            }
        }
        return List.copyOf(generatedFiles);
    }

    private static boolean hasGeneratedMarker(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.readString(file).lines().anyMatch(CGenTag::isGeneratedFile);
        } catch (IOException exception) {
            return false;
        }
    }

    /** One {@code ss.cli} invocation to make after every CGen-written file is on disk. */
    private record StatesmithRun(Path yamlSource, Path plantuml, Path smHeader, Path smSource) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onFileGenerated(int completed, int total, Path specSource, Path outputPath, boolean existed, int regionsCarried);
    }

    @FunctionalInterface
    public interface SwitchEnumConfirmation {
        /** Asked once per externally-resolved enum (not declared in any YAML enums:), before it's used. */
        boolean confirm(String enumType, Path sourceFile, List<String> members);
    }

    /**
     * Every enum the project already declares in YAML, as of the start of this generate() call -
     * never updated as enums get persisted mid-run, so a later lookup for a DIFFERENT owning spec
     * still goes through {@link #resolveSwitchEnum} and gets its own copy persisted too.
     */
    /**
     * Snapshot of every enum the project can resolve an @CGenSwitch against, taken once at the
     * start of generate() and never updated mid-run (a spec newly linked during this run must still
     * be looked up through {@link #resolveSwitchEnum}, so a second output needing the same enum for
     * a DIFFERENT owning spec gets its own link persisted too, not silently reused).
     *
     * @param declared enum type -> member names, from every {@code enums:} block in the project -
     *                 CGen owns and emits a typedef for these; never populated from a link.
     * @param links owning module.yaml -> (enum type -> the file, elsewhere, that already declares
     *              it) from that module's own {@code externalEnums:} - CGen never emits a typedef
     *              for these, only reads the given file to get the case list.
     */
    private record EnumIndex(Map<String, List<String>> declared, Map<Path, Map<String, Path>> links) {
    }

    private EnumIndex buildEnumIndex(ProjectConfig project) {
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (InterfaceSpec spec : loadInterfaces(project).values()) {
            registerEnums(declared, spec.enums(), spec.source());
        }
        Map<Path, Map<String, Path>> links = new LinkedHashMap<>();
        for (Path path : specificationFiles(project.root(), ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            registerEnums(declared, module.enums(), path);
            Map<String, Path> perModule = new LinkedHashMap<>();
            for (ModuleSpec.ExternalEnumLink link : module.externalEnums()) {
                perModule.put(link.name(), path.getParent().resolve(link.file()).normalize());
            }
            links.put(path, perModule);
        }
        return new EnumIndex(declared, links);
    }

    private List<String> resolveSwitchEnum(ProjectConfig project, String enumType, Path owningSpec, EnumIndex index,
                                           Map<Path, List<String>> linkedFileMembers, Map<String, ExternalEnumMatch> resolvedExternal,
                                           Map<Path, Set<String>> linkedThisRun, SwitchEnumConfirmation confirmation) {
        List<String> declared = index.declared().get(enumType);
        if (declared != null) {
            return declared;
        }
        Path linkedFile = index.links().getOrDefault(owningSpec, Map.of()).get(enumType);
        if (linkedFile != null) {
            return linkedFileMembers.computeIfAbsent(linkedFile, file -> readEnumMembersFromFile(file, enumType));
        }
        boolean alreadyLinkedThisRun = linkedThisRun.getOrDefault(owningSpec, Set.of()).contains(enumType);
        ExternalEnumMatch match = resolvedExternal.computeIfAbsent(enumType, type -> resolveExternalEnum(project, type, confirmation));
        if (!alreadyLinkedThisRun && isModuleSpec(owningSpec)) {
            String relativeFile = owningSpec.getParent().toAbsolutePath().normalize()
                    .relativize(match.file().toAbsolutePath().normalize()).toString().replace('\\', '/');
            projects.appendExternalEnumLink(owningSpec, enumType, relativeFile);
            linkedThisRun.computeIfAbsent(owningSpec, spec -> new LinkedHashSet<>()).add(enumType);
        }
        return match.members();
    }

    private static boolean isModuleSpec(Path specSource) {
        return specSource.getFileName().toString().endsWith(".module.yaml");
    }

    private static void registerEnums(Map<String, List<String>> declaredEnums, List<InterfaceSpec.EnumDef> enums, Path source) {
        for (InterfaceSpec.EnumDef enumDef : enums) {
            List<String> members = enumDef.values().stream().map(InterfaceSpec.EnumValue::name).toList();
            List<String> previous = declaredEnums.putIfAbsent(enumDef.name(), members);
            if (previous != null && !previous.equals(members)) {
                throw new CGenException("Enum '" + enumDef.name()
                        + "' is declared with different members in more than one YAML file (conflict found near " + source + ")");
            }
        }
    }

    private record ExternalEnumMatch(Path file, List<String> members) {
    }

    private static Pattern typedefEnumPattern(String enumType) {
        return Pattern.compile(
                "typedef\\s+enum\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\s*)?\\{([^}]*)\\}\\s*" + Pattern.quote(enumType) + "\\s*;",
                Pattern.DOTALL);
    }

    private static List<String> readEnumMembersFromFile(Path file, String enumType) {
        if (!Files.isRegularFile(file)) {
            throw new CGenException("Linked enum file " + file + " for '" + enumType + "' no longer exists");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException exception) {
            throw new CGenException("Cannot read linked enum file " + file + ": " + exception.getMessage(), exception);
        }
        Matcher matcher = typedefEnumPattern(enumType).matcher(text);
        if (!matcher.find()) {
            throw new CGenException(file + " no longer defines 'typedef enum { ... } " + enumType
                    + ";' - fix or remove its externalEnums: link");
        }
        return parseEnumMembers(matcher.group(1));
    }

    private List<ExternalEnumMatch> findExternalEnumMatches(ProjectConfig project, String enumType) {
        Pattern typedefEnum = typedefEnumPattern(enumType);
        List<Path> candidateFiles = new ArrayList<>();
        candidateFiles.addAll(specificationFiles(project.root(), ".h", project));
        candidateFiles.addAll(specificationFiles(project.root(), ".c", project));
        List<ExternalEnumMatch> matches = new ArrayList<>();
        for (Path path : candidateFiles) {
            String text;
            try {
                text = Files.readString(path);
            } catch (IOException exception) {
                continue;
            }
            Matcher matcher = typedefEnum.matcher(text);
            if (matcher.find()) {
                matches.add(new ExternalEnumMatch(path, parseEnumMembers(matcher.group(1))));
            }
        }
        return matches;
    }

    private ExternalEnumMatch resolveExternalEnum(ProjectConfig project, String enumType, SwitchEnumConfirmation confirmation) {
        List<ExternalEnumMatch> matches = findExternalEnumMatches(project, enumType);
        if (matches.isEmpty()) {
            throw new CGenException("@CGenSwitch " + enumType + " is not declared in any YAML enums: block, and no "
                    + "matching 'typedef enum { ... } " + enumType + ";' was found in the project's .h/.c files");
        }
        ExternalEnumMatch first = matches.get(0);
        for (ExternalEnumMatch other : matches) {
            if (!other.members().equals(first.members())) {
                throw new CGenException("@CGenSwitch " + enumType + " matches conflicting 'typedef enum' definitions in "
                        + first.file() + " and " + other.file() + " - remove the duplicate before generating");
            }
        }
        if (!confirmation.confirm(enumType, first.file(), first.members())) {
            throw new CGenException("@CGenSwitch " + enumType + ": use of the enum found in " + first.file() + " was not confirmed");
        }
        return first;
    }

    private static List<String> parseEnumMembers(String body) {
        List<String> members = new ArrayList<>();
        for (String rawEntry : body.split(",")) {
            Matcher nameMatch = C_IDENTIFIER.matcher(rawEntry.strip());
            if (nameMatch.find() && nameMatch.start() == 0) {
                members.add(nameMatch.group());
            }
        }
        if (members.isEmpty()) {
            throw new CGenException("Matched 'typedef enum' has no members");
        }
        return members;
    }

    /**
     * Existing module source (.c) files within scope, one per .module.yaml found - used by
     * {@code fix-prototypes} to scan already-generated files without re-rendering anything.
     */
    public List<Path> moduleSourceFiles(ProjectConfig project, Path scope) {
        List<Path> files = new ArrayList<>();
        for (Path path : specificationFiles(scope, ".module.yaml", project)) {
            ModuleSpec module = ModuleSpec.from(path, yamlFiles.load(path));
            Path source = module.source().getParent().resolve(module.sourceFile());
            if (Files.exists(source)) {
                files.add(source);
            }
        }
        return files;
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
            if (machine.engine() == StateMachineSpec.Engine.BUILTIN) {
                cleanOutputs(path.getParent(), List.of(machine.header(), machine.sourceFile()), cleaned);
            } else {
                String smDirectory = StateSmithNaming.smDirectoryName(machine.name());
                cleanOutputs(path.getParent(), List.of(
                        machine.header(), machine.sourceFile(),
                        StateSmithNaming.hooksHeaderFileName(machine.name()), StateSmithNaming.hooksSourceFileName(machine.name()),
                        smDirectory + "/" + StateSmithNaming.plantUmlFileName(machine.name()),
                        smDirectory + "/" + StateSmithNaming.smHeaderFileName(machine.name()),
                        smDirectory + "/" + StateSmithNaming.smSourceFileName(machine.name())),
                        cleaned);
            }
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

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new CGenException("Cannot scan " + directory + ": " + exception.getMessage(), exception);
        }
        return found.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    /** Thread count for {@link #walkDirectoriesParallel} - directory listing is I/O-bound, so this can far exceed core count. */
    private static final int PARALLEL_WALK_THREADS = 50;

    /**
     * Fast, multithreaded recursive directory count under {@code scope} (no cgen.yaml checking,
     * unlike {@link #findNestedProjectRoots}) - cheap enough to run, live, before asking whether to
     * proceed with the real (also multithreaded, cgen.yaml-checking) scan for {@code --also-nested}.
     * Reports the running total as it goes; may call it from multiple threads concurrently.
     */
    public int countDirectoriesFast(Path scope, IntConsumer onProgress) {
        return walkDirectoriesParallel(scope, (count, directory) -> onProgress.accept(count));
    }

    /**
     * Finds every {@code cgen.yaml} strictly inside {@code scope} (nested-of-nested included),
     * for {@code generate --also-nested}. Unlike {@link #specificationFiles}, this does not stop
     * descending at a nested project boundary - finding what's past it is the point.
     */
    public List<Path> findNestedProjectRoots(Path scope) {
        return findNestedProjectRoots(scope, (count, directory) -> { });
    }

    /** Same as {@link #findNestedProjectRoots(Path)}, reporting each directory visited (with a running count) as it walks. */
    public List<Path> findNestedProjectRoots(Path scope, DirectoryProgress onDirectoryVisited) {
        ConcurrentLinkedQueue<Path> found = new ConcurrentLinkedQueue<>();
        walkDirectoriesParallel(scope, (count, directory) -> {
            onDirectoryVisited.onDirectory(count, directory);
            if (!directory.equals(scope) && Files.isRegularFile(directory.resolve(ProjectService.PROJECT_FILE))) {
                found.add(directory.resolve(ProjectService.PROJECT_FILE));
            }
        });
        return found.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    @FunctionalInterface
    public interface DirectoryProgress {
        void onDirectory(int count, Path directory);
    }

    /**
     * Walks every directory under {@code scope} (including {@code scope} itself), {@code onDirectory}
     * called once per directory found with the running count and that directory's path. Unlike a
     * single-threaded {@code Files.walkFileTree}, up to {@value #PARALLEL_WALK_THREADS} directories
     * are listed concurrently - each directory's own subdirectories are handed off as new tasks to
     * the same pool rather than recursed into inline, so one thread blocked on a slow/large directory
     * listing doesn't stall the others. An inaccessible directory is skipped (its listing throws)
     * rather than aborting the walk. Returns the total directory count.
     */
    private int walkDirectoriesParallel(Path scope, DirectoryProgress onDirectory) {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_WALK_THREADS);
        AtomicInteger total = new AtomicInteger();
        Phaser phaser = new Phaser(1);
        try {
            submitDirectoryWalkTask(pool, phaser, scope, scope, total, onDirectory);
            phaser.arriveAndAwaitAdvance();
        } finally {
            pool.shutdown();
        }
        return total.get();
    }

    private static void submitDirectoryWalkTask(ExecutorService pool, Phaser phaser, Path scope, Path directory,
                                                AtomicInteger total, DirectoryProgress onDirectory) {
        phaser.register();
        pool.execute(() -> {
            try {
                if (isExcludedProjectPath(scope, directory)) {
                    return;
                }
                onDirectory.onDirectory(total.incrementAndGet(), directory);
                try (Stream<Path> entries = Files.list(directory)) {
                    entries.filter(Files::isDirectory).forEach(subdirectory ->
                            submitDirectoryWalkTask(pool, phaser, scope, subdirectory, total, onDirectory));
                } catch (IOException exception) {
                    // Inaccessible directory (permissions, a broken junction, ...) - skip it, don't abort the walk.
                }
            } finally {
                phaser.arriveAndDeregister();
            }
        });
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

    private record Output(Path specSource, Path path, String content, boolean existed, int regionsCarried) {
    }
}
