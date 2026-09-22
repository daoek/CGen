package com.daoekinc.pinfit.project;

import com.daoekinc.pinfit.PinfitException;
import com.daoekinc.pinfit.config.YamlFiles;
import com.daoekinc.pinfit.model.ProjectConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class ProjectService {
    public static final String PROJECT_FILE = "pinfit.yaml";
    /** The pre-rebrand project file name. Never read directly - see {@link #findAndLoad}. */
    public static final String LEGACY_PROJECT_FILE = "cgen.yaml";
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final YamlFiles yamlFiles;

    public ProjectService(YamlFiles yamlFiles) {
        this.yamlFiles = yamlFiles;
    }

    public Path init(Path requestedDirectory, boolean force) {
        Path root = requestedDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Path projectFile = root.resolve(PROJECT_FILE);
            String directoryName = root.getFileName() == null ? "c_project" : root.getFileName().toString();
            String content = """
                    # Pinfit project configuration
                    schema: 1
                    name: '%s'
                    version: 0.1.0

                    documentation:
                      style: doxygen # doxygen, none, or custom
                      # file: documentation.yaml

                    format:
                      indent: 4
                      lineEnding: lf
                      # suppressUnusedWarnings: false # emit (void)param; lines in generated stub bodies (default true)
                      # publicVariables: accessors # extern (default) or accessors (getter/setter functions)
                      # functionNaming: camelCase # snake_case (default) or camelCase for generated function names
                    """.formatted(directoryName.replace("'", "''"));
            StandardOpenOption existsOption = force ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE_NEW;
            Files.writeString(projectFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, existsOption);
            return projectFile;
        } catch (FileAlreadyExistsException exception) {
            throw new PinfitException(root.resolve(PROJECT_FILE) + " already exists; use -f/--force to overwrite it");
        } catch (IOException exception) {
            throw new PinfitException("Cannot initialize project at " + root + ": " + exception.getMessage(), exception);
        }
    }

    public ProjectConfig findAndLoad(Path start) {
        return findAndLoad(start, legacyFile -> false);
    }

    /**
     * Same as {@link #findAndLoad(Path)}, but a directory holding only a legacy {@code cgen.yaml}
     * (the project file's name before the Pinfit rebrand) is not loaded silently - Pinfit no
     * longer reads that name on its own, since a config-file rename is too easy to get wrong to
     * paper over automatically forever. Instead {@code confirmMigration} is asked whether to
     * migrate it in place; declining (or a caller that always answers false, like the single-arg
     * overload) refuses to generate at all until the file is migrated.
     */
    public ProjectConfig findAndLoad(Path start, Predicate<Path> confirmMigration) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path projectFile = projectFileIn(current);
            if (projectFile != null) {
                if (isLegacy(projectFile)) {
                    if (!confirmMigration.test(projectFile)) {
                        throw new PinfitException(projectFile + " is a legacy " + LEGACY_PROJECT_FILE
                                + " project file; Pinfit no longer reads that name directly and generating "
                                + "requires migrating it to " + PROJECT_FILE + " first.");
                    }
                    projectFile = migrateLegacyProjectFile(projectFile);
                }
                return load(projectFile);
            }
            current = current.getParent();
        }
        throw new PinfitException("No " + PROJECT_FILE + " found in this directory or its parents; run 'pinfit init' first");
    }

    /** Loads a project from a known {@code pinfit.yaml} path directly, e.g. one found under a nested project boundary. */
    public ProjectConfig load(Path projectFile) {
        return ProjectConfig.from(projectFile, yamlFiles.load(projectFile));
    }

    /** The project file in {@code dir} - {@link #PROJECT_FILE} if present, else the legacy name, else null. */
    public static Path projectFileIn(Path dir) {
        Path canonical = dir.resolve(PROJECT_FILE);
        if (Files.isRegularFile(canonical)) {
            return canonical;
        }
        Path legacy = dir.resolve(LEGACY_PROJECT_FILE);
        return Files.isRegularFile(legacy) ? legacy : null;
    }

    private static boolean isLegacy(Path projectFile) {
        return projectFile.getFileName().toString().equals(LEGACY_PROJECT_FILE);
    }

    /**
     * Renames a legacy {@code cgen.yaml} to {@link #PROJECT_FILE} in place, touching up its
     * leading {@code # CGen project configuration} comment (written by {@link #init} before the
     * rebrand) to match - every other line, including the rest of the user's own comments and
     * values, is kept exactly as written. Only called after the caller has confirmed the
     * migration; see {@link #findAndLoad(Path, Predicate)}.
     */
    public Path migrateLegacyProjectFile(Path legacy) {
        Path migrated = legacy.resolveSibling(PROJECT_FILE);
        try {
            String content = Files.readString(legacy, StandardCharsets.UTF_8);
            String updated = content.replaceFirst("(?m)^# CGen project configuration$", "# Pinfit project configuration");
            if (!updated.equals(content)) {
                Files.writeString(legacy, updated, StandardCharsets.UTF_8);
            }
            Files.move(legacy, migrated);
        } catch (IOException exception) {
            throw new PinfitException("Cannot migrate " + legacy + " to " + migrated + ": " + exception.getMessage(), exception);
        }
        return migrated;
    }

    /**
     * Adds a {@code { name: <enumType>, file: <relativeFilePath> }} entry under {@code specFile}'s
     * {@code externalEnums:} - a link to where an @PinfitSwitch enum is actually declared (found by
     * scanning .h/.c files, confirmed by the user), not a copy of it: Pinfit must never also declare
     * it under {@code enums:}, or the generated header would redefine a type that already exists,
     * breaking the build. A plain text insertion (not a YAML re-serialization) so the rest of the
     * hand-authored file, comments included, is untouched; verified by re-parsing afterward, with
     * the change rolled back if that fails, since this is the one place Pinfit writes into a file it
     * doesn't fully own and generate.
     */
    public void appendExternalEnumLink(Path specFile, String enumType, String relativeFilePath) {
        String original;
        try {
            original = Files.readString(specFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PinfitException("Cannot read " + specFile + ": " + exception.getMessage(), exception);
        }
        String lineEnding = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(original.replace("\r\n", "\n").split("\n", -1)));
        boolean trailingNewline = !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty();
        if (trailingNewline) {
            lines.remove(lines.size() - 1);
        }
        List<String> updated = insertExternalEnumLink(lines, enumType, relativeFilePath, specFile);
        String updatedText = String.join(lineEnding, updated) + (trailingNewline ? lineEnding : "");
        try {
            Files.writeString(specFile, updatedText, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PinfitException("Cannot write " + specFile + ": " + exception.getMessage(), exception);
        }
        try {
            yamlFiles.load(specFile);
        } catch (RuntimeException exception) {
            try {
                Files.writeString(specFile, original, StandardCharsets.UTF_8);
            } catch (IOException rollbackFailure) {
                throw new PinfitException(specFile + " was left in a broken state while linking enum '" + enumType
                        + "' and could not be rolled back automatically: " + rollbackFailure.getMessage(), rollbackFailure);
            }
            throw new PinfitException(specFile + ": could not link enum '" + enumType
                    + "' automatically (change rolled back) - add it under externalEnums: by hand instead: " + exception.getMessage(),
                    exception);
        }
    }

    private static List<String> insertExternalEnumLink(List<String> lines, String enumType, String relativeFilePath, Path specFile) {
        String entry = "  - { name: " + enumType + ", file: " + relativeFilePath + " }";
        int keyIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("externalEnums: []") || lines.get(i).matches("externalEnums:\\s*")) {
                keyIndex = i;
                break;
            }
        }
        List<String> updated = new ArrayList<>();
        if (keyIndex < 0) {
            int includesIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).matches("includes:.*")) {
                    includesIndex = i;
                    break;
                }
            }
            if (includesIndex < 0) {
                throw new PinfitException(specFile + ": cannot find 'includes:' to add a new 'externalEnums:' block near - "
                        + "add 'externalEnums: []' to this file once, then regenerate");
            }
            int insertAt = endOfKeyBlock(lines, includesIndex);
            updated.addAll(lines.subList(0, insertAt));
            updated.add("");
            updated.add("externalEnums:");
            updated.add(entry);
            updated.addAll(lines.subList(insertAt, lines.size()));
        } else if (lines.get(keyIndex).equals("externalEnums: []")) {
            updated.addAll(lines.subList(0, keyIndex));
            updated.add("externalEnums:");
            updated.add(entry);
            updated.addAll(lines.subList(keyIndex + 1, lines.size()));
        } else {
            int blockEnd = endOfIndentedBlock(lines, keyIndex + 1);
            updated.addAll(lines.subList(0, blockEnd));
            updated.add(entry);
            updated.addAll(lines.subList(blockEnd, lines.size()));
        }
        return updated;
    }

    private static int endOfKeyBlock(List<String> lines, int keyLineIndex) {
        if (!lines.get(keyLineIndex).matches("[A-Za-z]+:\\s*")) {
            return keyLineIndex + 1;
        }
        return endOfIndentedBlock(lines, keyLineIndex + 1);
    }

    private static int endOfIndentedBlock(List<String> lines, int startIndex) {
        int i = startIndex;
        while (i < lines.size() && !lines.get(i).isBlank() && (lines.get(i).startsWith(" ") || lines.get(i).startsWith("\t"))) {
            i++;
        }
        return i;
    }

    public Path createInterface(ProjectConfig project, String requestedName, Path requestedDirectory) {
        String name = identifier(requestedName, "interface name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".interface.yaml");
        String content = """
                kind: interface
                name: %s
                description: Portable %s interface
                header: %s_I.h

                # Used by generated wrappers when validation fails. For a return type that -1 does
                # not fit, key a default by type instead:
                # invalidReturns:
                #   flash_command_t: FLASH_COMMAND_NONE
                invalidReturn: -1
                uninitializedReturn: -2

                includes: []

                # enums:
                #   - name: mode_t
                #     description: Operating mode
                #     values:
                #       - { name: MODE_OFF, value: 0 }
                #       - { name: MODE_ON }
                enums: []

                # structs:
                #   - name: config_t
                #     description: Interface configuration
                #     fields:
                #       - uint32_t baud_rate
                #       - bool parity_enabled
                structs: []

                functions:
                  - name: init
                    return: int
                    description: Initialize the interface
                    parameters: []
                    # invalidReturn: -3       # override invalidReturn for just this function
                    # uninitializedReturn: -4 # override uninitializedReturn for just this function
                """.formatted(name, name, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createModule(ProjectConfig project, String requestedName, List<String> requestedInterfaces, Path requestedDirectory) {
        String name = identifier(requestedName, "module name");
        List<String> interfaces = requestedInterfaces.stream().map(value -> identifier(value, "interface name")).toList();
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".module.yaml");
        StringBuilder implemented = new StringBuilder();
        if (interfaces.isEmpty()) {
            implemented.append(" []");
        } else {
            for (String interfaceName : interfaces) {
                implemented.append("\n  - ").append(interfaceName);
            }
        }
        String content = """
                kind: module
                name: %s
                description: Concrete %s implementation
                header: %s.h
                source: %s.c

                implements:%s
                includes: []

                # enums:
                #   - name: mode_t
                #     description: Operating mode
                #     values:
                #       - { name: MODE_OFF, value: 0 }
                #       - { name: MODE_ON }
                enums: []

                # Members stored in %s_context_t.
                context: []

                # visibility is public (extern in header) or private (static in source).
                # variables:
                #   - uint32_t transfer_count public   # compact form: "type name [public]"
                #   - { type: bool, name: busy, visibility: private, initial: 'false', description: Busy flag }
                variables: []

                # Standalone functions (a function.<name>.body user region each), separate from
                # any implemented interface's functions. visibility is private (static, default)
                # or public (declared in the header).
                # functions:
                #   - name: initialize
                #     return: bool
                #     description: One-time module initialization
                #     parameters: []
                #     invalidReturn: false   # guard value for a non-void return type
                #     visibility: public
                functions: []

                # singleton: true generates a <name>_instance() accessor with lazy init.
                singleton: false
                # instance: %s_instance # rename the generated singleton accessor function
                # singletonElse: true # also emit an else branch (singleton.else) for already-initialized calls
                """.formatted(name, name, name, name, implemented, name, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createStateMachine(ProjectConfig project, String requestedName, Path requestedDirectory) {
        return createStateMachine(project, requestedName, requestedDirectory, "builtin");
    }

    public Path createStateMachine(ProjectConfig project, String requestedName, Path requestedDirectory, String engine) {
        String name = identifier(requestedName, "state machine name");
        if (!engine.equals("builtin") && !engine.equals("statesmith")) {
            throw new PinfitException("--engine must be builtin or statesmith, got '" + engine + "'");
        }
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".state-machine.yaml");
        String content = engine.equals("statesmith") ? statesmithStateMachineTemplate(name) : builtinStateMachineTemplate(name);
        writeNew(spec, content);
        return spec;
    }

    private static String builtinStateMachineTemplate(String name) {
        return """
                kind: state-machine
                name: %s
                description: %s state machine
                header: %s.h
                source: %s.c

                includes: []

                # Members stored in %s_context_t alongside the state.
                context: []

                initial: IDLE

                states:
                  - { name: IDLE, description: Waiting to start }
                  - { name: RUNNING, description: In progress }

                events:
                  - name: START
                    description: Begin running
                    parameters: []
                    # parameters:
                    #   - uint32_t speed

                # guard: true adds a transition.<from>.<event>.guard user region that sets
                # pinfit_guard = false to block the transition at runtime.
                transitions:
                  - { from: IDLE, event: START, to: RUNNING, guard: false }
                  # - { from: IDLE, event: START, to: RUNNING, guard: false, description: Start the run }
                """.formatted(name, name, name, name, name);
    }

    private static String statesmithStateMachineTemplate(String name) {
        return """
                # Requires a `stateSmith:` block in pinfit.yaml, e.g.:
                #   stateSmith:
                #     command: ss.cli
                #     version: 0.22.2
                kind: state-machine
                engine: statesmith
                name: %s
                description: %s state machine
                header: %s.h
                source: %s.c

                includes: []

                # Members stored in %s_context_t alongside the StateSmith instance.
                context: []

                initial: IDLE

                states:
                  - { name: IDLE, description: Waiting to start }
                  - name: RUNNING           # composite state - nest its own sub-states
                    initial: RUN_STEADY      # required: RUNNING is a transition target below
                    states:
                      - { name: RUN_STEADY, description: Normal operation }
                      - { name: RUN_FAULT, description: Fault detected while running }

                events:
                  - name: START
                    description: Begin running
                    parameters: []
                    # parameters:
                    #   - uint32_t speed
                  - { name: FAULT, description: A fault occurred }

                # guard: true adds a transition.<from>.<event>.guard user region that sets
                # pinfit_guard = false to block the transition at runtime. event: tick is reserved
                # for a polled transition (StateSmith's do event) instead of a declared event.
                transitions:
                  - { from: IDLE, event: START, to: RUNNING, guard: false }
                  - { from: RUNNING, event: FAULT, to: RUN_FAULT, guard: false } # covers every RUNNING child
                  - { from: RUN_STEADY, event: tick, to: RUN_STEADY, guard: true } # polled condition, no-op target
                """.formatted(name, name, name, name, name);
    }

    public Path createObserver(ProjectConfig project, String requestedName, String requestedInterface, int capacity, Path requestedDirectory) {
        String name = identifier(requestedName, "observer name");
        String interfaceName = identifier(requestedInterface, "interface name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".observer.yaml");
        String content = """
                kind: observer
                name: %s
                description: %s subscriber list
                header: %s.h
                source: %s.c

                includes: []

                # Interface every subscriber implements; all its functions must return void.
                interface: %s
                capacity: %d

                # Members stored in %s_context_t alongside the subscriber list.
                context: []
                """.formatted(name, name, name, name, interfaceName, capacity, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createCommandTable(ProjectConfig project, String requestedName, Path requestedDirectory) {
        String name = identifier(requestedName, "command table name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".command-table.yaml");
        String content = """
                kind: command-table
                name: %s
                description: %s command dispatch table
                header: %s.h
                source: %s.c

                includes: []

                # Members stored in %s_context_t.
                context: []

                # opcode is optional; give every command one explicitly, or omit it on all
                # of them to auto-number starting at 0.
                commands:
                  - { name: PING, opcode: 0, description: Respond with a heartbeat }
                """.formatted(name, name, name, name, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createStatusCodes(ProjectConfig project, String requestedName, Path requestedDirectory) {
        String name = identifier(requestedName, "status codes name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".status-codes.yaml");
        String content = """
                kind: status-codes
                name: %s
                description: %s status codes
                header: %s.h

                includes: []

                # Exactly one code must have value 0; it becomes the success value used by
                # the generated _SUCCEEDED/_FAILED/_CHECK macros.
                codes:
                  - { name: OK, value: 0, description: Success }
                  - { name: INVALID_PARAM, value: -1, description: Invalid parameter }
                  - { name: NOT_READY, value: -2, description: Not ready }
                """.formatted(name, name, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createAdapter(ProjectConfig project, String requestedName, String requestedFrom, String requestedTo, Path requestedDirectory) {
        String name = identifier(requestedName, "adapter name");
        String from = identifier(requestedFrom, "interface name");
        String to = identifier(requestedTo, "interface name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".adapter.yaml");
        String content = """
                kind: adapter
                name: %s
                description: Adapts %s to %s
                header: %s.h
                source: %s.c

                includes: []

                # 'from' is the interface this adapter exposes to callers.
                # 'to' is the interface it calls into (bound at runtime via %s_set_target).
                from: %s
                to: %s

                # Members stored in %s_context_t alongside the target pointer.
                context: []

                # Functions listed here call straight through to 'to' when their
                # signatures match exactly. Leave a 'from' function unmapped to hand-write it.
                mappings: []
                """.formatted(name, from, to, name, name, name, from, to, name);
        writeNew(spec, content);
        return spec;
    }

    public Path safeDirectory(ProjectConfig project, Path requested) {
        Path normalized = requested.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            Path realRoot = project.root().toRealPath();
            Path realDirectory = normalized.toRealPath();
            if (!realDirectory.startsWith(realRoot)) {
                throw new PinfitException("Path resolves outside project: " + requested);
            }
            requireNoNestedProjectBoundary(realRoot, realDirectory, requested);
            return realDirectory;
        } catch (IOException exception) {
            throw new PinfitException("Cannot create directory " + normalized + ": " + exception.getMessage(), exception);
        }
    }

    public Path existingDirectory(ProjectConfig project, Path requested) {
        Path normalized = requested.toAbsolutePath().normalize();
        try {
            Path realRoot = project.root().toRealPath();
            Path realDirectory = normalized.toRealPath();
            if (!Files.isDirectory(realDirectory) || !realDirectory.startsWith(realRoot)) {
                throw new PinfitException("Directory must exist inside the project: " + requested);
            }
            requireNoNestedProjectBoundary(realRoot, realDirectory, requested);
            return realDirectory;
        } catch (IOException exception) {
            throw new PinfitException("Directory must exist inside the project: " + normalized, exception);
        }
    }

    /**
     * A subdirectory holding its own {@code pinfit.yaml} is a separate, self-contained project
     * (e.g. an imported library) - not scope of the enclosing one. Crossing into it from the
     * enclosing project (creating specs there, or generating/scanning into it) would apply the
     * wrong rules and could overwrite it; that subtree is only ever touched by running Pinfit
     * from inside it, where {@link #findAndLoad} resolves its own {@code pinfit.yaml}.
     */
    private static void requireNoNestedProjectBoundary(Path root, Path directory, Path requested) {
        Path current = directory;
        while (current != null && !current.equals(root)) {
            if (projectFileIn(current) != null) {
                throw new PinfitException(requested + " is inside a separate project rooted at " + current
                        + " (it has its own " + PROJECT_FILE + "); run pinfit commands from inside that project instead");
            }
            current = current.getParent();
        }
    }

    private static String identifier(String value, String label) {
        if (!C_IDENTIFIER.matcher(value).matches()) {
            throw new PinfitException(label + " must be a valid C identifier, got '" + value + "'");
        }
        return value;
    }

    private static void writeNew(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            throw new PinfitException(path + " already exists; create will not overwrite it");
        } catch (IOException exception) {
            throw new PinfitException("Cannot write " + path + ": " + exception.getMessage(), exception);
        }
    }
}
