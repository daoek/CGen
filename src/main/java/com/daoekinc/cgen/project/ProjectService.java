package com.daoekinc.cgen.project;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.model.ProjectConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;

public final class ProjectService {
    public static final String PROJECT_FILE = "cgen.yaml";
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final YamlFiles yamlFiles;

    public ProjectService(YamlFiles yamlFiles) {
        this.yamlFiles = yamlFiles;
    }

    public Path init(Path requestedDirectory) {
        Path root = requestedDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Path projectFile = root.resolve(PROJECT_FILE);
            String directoryName = root.getFileName() == null ? "c_project" : root.getFileName().toString();
            String content = """
                    # CGen project configuration
                    schema: 1
                    name: '%s'
                    version: 0.1.0

                    documentation:
                      style: doxygen # doxygen, none, or custom
                      # file: documentation.yaml

                    format:
                      indent: 4
                      lineEnding: lf
                    """.formatted(directoryName.replace("'", "''"));
            Files.writeString(projectFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return projectFile;
        } catch (FileAlreadyExistsException exception) {
            throw new CGenException(root.resolve(PROJECT_FILE) + " already exists; init will not overwrite it");
        } catch (IOException exception) {
            throw new CGenException("Cannot initialize project at " + root + ": " + exception.getMessage(), exception);
        }
    }

    public ProjectConfig findAndLoad(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path projectFile = current.resolve(PROJECT_FILE);
            if (Files.isRegularFile(projectFile)) {
                return ProjectConfig.from(projectFile, yamlFiles.load(projectFile));
            }
            current = current.getParent();
        }
        throw new CGenException("No " + PROJECT_FILE + " found in this directory or its parents; run 'CGen init' first");
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

                # Used by generated wrappers when validation fails.
                invalidReturn: -1
                uninitializedReturn: -2

                includes: []
                enums: []
                structs: []

                functions:
                  - name: init
                    return: int
                    description: Initialize the interface
                    parameters: []
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

                # Members stored in %s_context_t.
                context: []

                # visibility is public (extern in header) or private (static in source).
                variables: []
                """.formatted(name, name, name, name, implemented, name);
        writeNew(spec, content);
        return spec;
    }

    public Path createStateMachine(ProjectConfig project, String requestedName, Path requestedDirectory) {
        String name = identifier(requestedName, "state machine name");
        Path directory = safeDirectory(project, requestedDirectory);
        Path spec = directory.resolve(name + ".state-machine.yaml");
        String content = """
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

                transitions:
                  - { from: IDLE, event: START, to: RUNNING, guard: false }
                """.formatted(name, name, name, name, name);
        writeNew(spec, content);
        return spec;
    }

    public Path safeDirectory(ProjectConfig project, Path requested) {
        Path normalized = requested.toAbsolutePath().normalize();
        if (!normalized.startsWith(project.root())) {
            throw new CGenException("Path escapes project: " + requested);
        }
        try {
            Files.createDirectories(normalized);
            Path realRoot = project.root().toRealPath();
            Path realDirectory = normalized.toRealPath();
            if (!realDirectory.startsWith(realRoot)) {
                throw new CGenException("Path resolves outside project: " + requested);
            }
            return realDirectory;
        } catch (IOException exception) {
            throw new CGenException("Cannot create directory " + normalized + ": " + exception.getMessage(), exception);
        }
    }

    public Path existingDirectory(ProjectConfig project, Path requested) {
        Path normalized = requested.toAbsolutePath().normalize();
        if (!normalized.startsWith(project.root())) {
            throw new CGenException("Path escapes project: " + requested);
        }
        try {
            Path realRoot = project.root().toRealPath();
            Path realDirectory = normalized.toRealPath();
            if (!Files.isDirectory(realDirectory) || !realDirectory.startsWith(realRoot)) {
                throw new CGenException("Directory must exist inside the project: " + requested);
            }
            return realDirectory;
        } catch (IOException exception) {
            throw new CGenException("Directory must exist inside the project: " + normalized, exception);
        }
    }

    private static String identifier(String value, String label) {
        if (!C_IDENTIFIER.matcher(value).matches()) {
            throw new CGenException(label + " must be a valid C identifier, got '" + value + "'");
        }
        return value;
    }

    private static void writeNew(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            throw new CGenException(path + " already exists; create will not overwrite it");
        } catch (IOException exception) {
            throw new CGenException("Cannot write " + path + ": " + exception.getMessage(), exception);
        }
    }
}
