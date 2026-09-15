package com.daoekinc.cgen.cli;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.generate.CGenerator;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.project.ProjectService;
import com.daoekinc.cgen.tag.TagHelper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CGenCli {
    private static final String RESET = "\u001B[0m";
    private static final String RED_BOLD = "\u001B[1;31m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN_BOLD = "\u001B[1;36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW_BOLD = "\u001B[1;33m";

    private final Path workingDirectory;
    private final BufferedReader input;
    private final PrintStream out;
    private final PrintStream err;
    private final ProjectService projects;
    private final CGenerator generator;

    public CGenCli(Path workingDirectory, PrintStream out, PrintStream err) {
        this(workingDirectory, System.in, out, err);
    }

    public CGenCli(Path workingDirectory, InputStream input, PrintStream out, PrintStream err) {
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.out = out;
        this.err = err;
        YamlFiles yaml = new YamlFiles();
        projects = new ProjectService(yaml);
        generator = new CGenerator(yaml, new TagHelper(), projects);
    }

    public int run(String... args) {
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
                usage(out);
                return 0;
            }
            return switch (args[0]) {
                case "init" -> init(args);
                case "create" -> create(args);
                case "gen", "generate" -> generate(args);
                case "rename" -> rename(args);
                case "detach" -> detach(args);
                default -> throw new CGenException("Unknown command '" + args[0] + "'");
            };
        } catch (CGenException exception) {
            printError(exception);
            return 1;
        }
    }

    private void printError(CGenException exception) {
        err.println();
        err.println(RED_BOLD + "CGen error" + RESET);
        err.println(RED + exception.getMessage() + RESET);
        if (exception.helpText() != null) {
            err.println();
            err.println(CYAN_BOLD + exception.helpTitle() + RESET);
            err.println(GREEN + exception.helpText() + RESET);
        }
        err.println();
    }

    private int init(String[] args) {
        boolean force = false;
        Path directory = workingDirectory;
        boolean directorySpecified = false;
        for (int index = 1; index < args.length; index++) {
            if (args[index].equals("-f") || args[index].equals("--force")) {
                force = true;
            } else if (!directorySpecified) {
                directory = workingDirectory.resolve(args[index]).normalize();
                directorySpecified = true;
            } else {
                throw new CGenException("Usage: CGen init [directory] [-f|--force]");
            }
        }
        Path file = projects.init(directory, force);
        out.println("Created " + file);
        return 0;
    }

    private int create(String[] args) {
        if (args.length < 3) {
            throw new CGenException("Usage: CGen create <interface|module|state-machine|observer|command-table|status-codes|adapter> <name> [directory] [...]");
        }
        ProjectConfig project = projects.findAndLoad(workingDirectory);
        if (args[1].equals("interface")) {
            Path directory = parseSimpleDirectory(args, "Usage: CGen create interface <name> [directory]");
            out.println("Created " + projects.createInterface(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("module")) {
            List<String> interfaces = new ArrayList<>();
            Path directory = workingDirectory;
            boolean directorySpecified = false;
            int index = 3;
            while (index < args.length) {
                if (args[index].equals("--implements") && index + 1 < args.length) {
                    interfaces.addAll(Arrays.stream(args[index + 1].split(","))
                            .map(String::trim).filter(value -> !value.isEmpty()).toList());
                    index += 2;
                } else if (args[index].equals("--dir") && index + 1 < args.length && !directorySpecified) {
                    directory = resolveDirectory(args[index + 1]);
                    directorySpecified = true;
                    index += 2;
                } else if (!args[index].startsWith("--") && !directorySpecified) {
                    directory = resolveDirectory(args[index]);
                    directorySpecified = true;
                    index++;
                } else {
                    throw new CGenException("Usage: CGen create module <name> [directory] [--implements <name>[,<name>...]]");
                }
            }
            out.println("Created " + projects.createModule(project, args[2], interfaces, directory));
            return 0;
        }
        if (args[1].equals("state-machine")) {
            Path directory = parseSimpleDirectory(args, "Usage: CGen create state-machine <name> [directory]");
            out.println("Created " + projects.createStateMachine(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("command-table")) {
            Path directory = parseSimpleDirectory(args, "Usage: CGen create command-table <name> [directory]");
            out.println("Created " + projects.createCommandTable(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("status-codes")) {
            Path directory = parseSimpleDirectory(args, "Usage: CGen create status-codes <name> [directory]");
            out.println("Created " + projects.createStatusCodes(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("observer")) {
            String interfaceName = null;
            int capacity = 8;
            Path directory = workingDirectory;
            boolean directorySpecified = false;
            int index = 3;
            while (index < args.length) {
                if (args[index].equals("--interface") && index + 1 < args.length) {
                    interfaceName = args[index + 1];
                    index += 2;
                } else if (args[index].equals("--capacity") && index + 1 < args.length) {
                    try {
                        capacity = Integer.parseInt(args[index + 1]);
                    } catch (NumberFormatException exception) {
                        throw new CGenException("--capacity must be an integer");
                    }
                    index += 2;
                } else if (args[index].equals("--dir") && index + 1 < args.length && !directorySpecified) {
                    directory = resolveDirectory(args[index + 1]);
                    directorySpecified = true;
                    index += 2;
                } else if (!args[index].startsWith("--") && !directorySpecified) {
                    directory = resolveDirectory(args[index]);
                    directorySpecified = true;
                    index++;
                } else {
                    throw new CGenException("Usage: CGen create observer <name> --interface <name> [directory] [--capacity <n>]");
                }
            }
            if (interfaceName == null) {
                throw new CGenException("Usage: CGen create observer <name> --interface <name> [directory] [--capacity <n>]");
            }
            out.println("Created " + projects.createObserver(project, args[2], interfaceName, capacity, directory));
            return 0;
        }
        if (args[1].equals("adapter")) {
            String from = null;
            String to = null;
            Path directory = workingDirectory;
            boolean directorySpecified = false;
            int index = 3;
            while (index < args.length) {
                if (args[index].equals("--from") && index + 1 < args.length) {
                    from = args[index + 1];
                    index += 2;
                } else if (args[index].equals("--to") && index + 1 < args.length) {
                    to = args[index + 1];
                    index += 2;
                } else if (args[index].equals("--dir") && index + 1 < args.length && !directorySpecified) {
                    directory = resolveDirectory(args[index + 1]);
                    directorySpecified = true;
                    index += 2;
                } else if (!args[index].startsWith("--") && !directorySpecified) {
                    directory = resolveDirectory(args[index]);
                    directorySpecified = true;
                    index++;
                } else {
                    throw new CGenException("Usage: CGen create adapter <name> --from <interface> --to <interface> [directory]");
                }
            }
            if (from == null || to == null) {
                throw new CGenException("Usage: CGen create adapter <name> --from <interface> --to <interface> [directory]");
            }
            out.println("Created " + projects.createAdapter(project, args[2], from, to, directory));
            return 0;
        }
        throw new CGenException("Create type must be interface, module, state-machine, observer, command-table, status-codes, or adapter");
    }

    private int generate(String[] args) {
        boolean force = false;
        boolean verbose = false;
        boolean alsoNested = false;
        Path directory = workingDirectory;
        boolean directorySpecified = false;
        for (int index = 1; index < args.length; index++) {
            if (args[index].equals("-f") || args[index].equals("--force")) {
                force = true;
            } else if (args[index].equals("-v") || args[index].equals("--verbose")) {
                verbose = true;
            } else if (args[index].equals("--also-nested")) {
                alsoNested = true;
            } else if (!directorySpecified) {
                directory = resolveDirectory(args[index]);
                directorySpecified = true;
            } else {
                throw new CGenException("Usage: CGen generate [directory] [-f|--force] [-v|--verbose] [--also-nested]");
            }
        }
        ProjectConfig project;
        try {
            project = projects.findAndLoad(workingDirectory);
        } catch (CGenException exception) {
            if (!alsoNested) {
                throw exception;
            }
            project = null;
        }

        Path scope = null;
        Path nestedScanRoot;
        if (project != null) {
            scope = projects.existingDirectory(project, directory);
            nestedScanRoot = scope;
        } else {
            nestedScanRoot = directory;
            if (!Files.isDirectory(nestedScanRoot)) {
                throw new CGenException("Directory does not exist: " + nestedScanRoot);
            }
        }

        int knownDirectoryCount = 0;
        if (alsoNested) {
            AlsoNestedDecision decision = confirmAlsoNestedGenerate(nestedScanRoot);
            if (!decision.confirmed()) {
                out.println();
                out.println("Cancelled. No files were generated.");
                return 1;
            }
            knownDirectoryCount = decision.directoryCount();
        }

        List<Path> files = new ArrayList<>();
        if (project != null) {
            if (verbose) {
                out.println("Project root: " + project.root());
                out.println("Scope: " + scope);
            }
            files.addAll(generator.generate(project, scope, force, progressListener(project, verbose), switchEnumConfirmation(project)));
        } else if (verbose) {
            out.println("No cgen.yaml found at or above " + workingDirectory
                    + " - scanning " + nestedScanRoot + " for nested projects only (--also-nested)");
        }

        if (alsoNested) {
            for (Path nestedProjectFile : scanForNestedProjects(nestedScanRoot, knownDirectoryCount)) {
                ProjectConfig nestedProject = projects.load(nestedProjectFile);
                if (verbose) {
                    out.println("Nested project: " + nestedProject.root());
                }
                files.addAll(generator.generate(nestedProject, nestedProject.root(), force,
                        progressListener(nestedProject, verbose), switchEnumConfirmation(nestedProject)));
            }
        }
        if (!files.isEmpty()) {
            out.println();
            if (alsoNested) {
                out.println("Generated files:");
                for (Path file : files) {
                    out.println("  " + displayPath(file));
                }
                out.println();
            }
        }
        out.println(files.size() + " file(s) generated");
        return 0;
    }

    private record AlsoNestedDecision(boolean confirmed, int directoryCount) {
    }

    private AlsoNestedDecision confirmAlsoNestedGenerate(Path scanRoot) {
        out.println();
        out.println(YELLOW_BOLD + "--also-nested walks every subdirectory under " + scanRoot
                + " looking for nested cgen.yaml projects." + RESET);
        out.println("On a large or deep directory (an entire drive, say) that can take a while.");
        int[] counted = {0};
        long start = System.nanoTime();
        generator.countDirectoriesFast(scanRoot, count -> {
            counted[0] = count;
            if (count == 1 || count % 200 == 0) {
                out.print("\rCounting... " + count + " director" + (count == 1 ? "y" : "ies") + " found so far");
                out.flush();
            }
        });
        out.print("\rFound " + counted[0] + " director" + (counted[0] == 1 ? "y" : "ies") + " under " + scanRoot
                + " (" + Math.max(1, (System.nanoTime() - start) / 1_000_000) + " ms).                              \n");
        out.print("Search all of them for nested cgen.yaml projects and generate what's found? [y/N]: ");
        out.flush();
        String answer;
        try {
            answer = input.readLine();
        } catch (IOException exception) {
            throw new CGenException("Cannot read confirmation: " + exception.getMessage(), exception);
        }
        boolean confirmed = answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
        return new AlsoNestedDecision(confirmed, counted[0]);
    }

    private List<Path> scanForNestedProjects(Path scanRoot, int knownDirectoryCount) {
        int total = Math.max(knownDirectoryCount, 1);
        List<Path> found = generator.findNestedProjectRoots(scanRoot,
                (count, directory) -> printProgress(Math.min(count, total), total));
        out.println();
        out.println("Found " + found.size() + " nested project" + (found.size() == 1 ? "" : "s") + ".");
        return found;
    }

    private Path displayPath(Path file) {
        try {
            return workingDirectory.relativize(file);
        } catch (IllegalArgumentException exception) {
            return file;
        }
    }

    private CGenerator.SwitchEnumConfirmation switchEnumConfirmation(ProjectConfig project) {
        return (enumType, sourceFile, members) -> {
            out.println();
            out.println(YELLOW_BOLD + "@CGenSwitch " + enumType + " is not declared in any YAML enums: block." + RESET);
            out.println("Found a matching 'typedef enum' in " + project.root().relativize(sourceFile) + ":");
            out.println("  " + String.join(", ", members));
            out.print("Use this enum? [y/N]: ");
            out.flush();
            String answer;
            try {
                answer = input.readLine();
            } catch (IOException exception) {
                throw new CGenException("Cannot read confirmation: " + exception.getMessage(), exception);
            }
            return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
        };
    }

    private CGenerator.ProgressListener progressListener(ProjectConfig project, boolean verbose) {
        if (!verbose) {
            return (completed, total, specSource, outputPath, existed, regionsCarried) ->
                    printProgress(completed, total, project.root().relativize(outputPath));
        }
        return (completed, total, specSource, outputPath, existed, regionsCarried) -> {
            String status = !existed ? "new file"
                    : regionsCarried == 0 ? "regenerated, no user regions found"
                    : "regenerated, " + regionsCarried + " user region(s) carried over";
            out.println("[" + completed + "/" + total + "] " + project.root().relativize(specSource)
                    + " -> " + project.root().relativize(outputPath) + " (" + status + ")");
        };
    }

    private void printProgress(int completed, int total, Path relativePath) {
        int width = 30;
        int filled = (int) Math.round((completed / (double) total) * width);
        String bar = GREEN + "#".repeat(filled) + RESET + "-".repeat(width - filled);
        out.print("\r[" + bar + "] " + completed + "/" + total + "  " + relativePath + "[K");
        out.flush();
    }

    /**
     * Same bar as {@link #printProgress(int, int, Path)} but no trailing label - a per-directory
     * path there changes length every call, which is what turned into "full output of the directory
     * scanned" instead of one simple, steady progress bar.
     */
    private void printProgress(int completed, int total) {
        int width = 30;
        int filled = (int) Math.round((completed / (double) total) * width);
        String bar = GREEN + "#".repeat(filled) + RESET + "-".repeat(width - filled);
        out.print("\r[" + bar + "] " + completed + "/" + total + " [K");
        out.flush();
    }

    private int rename(String[] args) {
        if (args.length != 4 || !args[1].equals("module")) {
            throw new CGenException("Usage: CGen rename module <old-name> <new-name>");
        }
        ProjectConfig project = projects.findAndLoad(workingDirectory);
        CGenerator.RenameResult result = generator.renameModule(project, args[2], args[3]);
        for (CGenerator.Move move : result.movedFiles()) {
            out.println("Moved " + project.root().relativize(move.from()) + " -> " + project.root().relativize(move.to()));
        }
        out.println("Renamed module '" + result.oldName() + "' to '" + result.newName() + "'");
        List<Path> files = generator.generate(project, project.root(), false, progressListener(project, false));
        if (!files.isEmpty()) {
            out.println();
        }
        out.println(files.size() + " file(s) generated");
        return 0;
    }

    private int detach(String[] args) {
        if (args.length != 1) {
            throw new CGenException("Usage: CGen detach");
        }
        ProjectConfig project = projects.findAndLoad(workingDirectory);
        out.println();
        out.println(RED_BOLD + "DESTRUCTIVE: detach CGen from this project" + RESET);
        out.println(YELLOW_BOLD + "This removes all CGen tags and CGen-owned YAML configuration." + RESET);
        out.println("Generated C code and unrelated YAML files are kept.");
        out.println();
        out.print("Type the project name '" + project.name() + "' to continue: ");
        out.flush();
        String confirmation;
        try {
            confirmation = input.readLine();
        } catch (IOException exception) {
            throw new CGenException("Cannot read detach confirmation: " + exception.getMessage(), exception);
        }
        if (!project.name().equals(confirmation)) {
            out.println();
            out.println("Detach cancelled. No files were changed.");
            return 1;
        }

        CGenerator.DetachResult result = generator.detach(project);
        result.cleanedFiles().forEach(path -> out.println("Removed tags from " + project.root().relativize(path)));
        result.deletedConfigurationFiles().forEach(path -> out.println("Deleted " + project.root().relativize(path)));
        out.println("CGen detached from '" + project.name() + "'.");
        return 0;
    }

    private Path resolveDirectory(String value) {
        return workingDirectory.resolve(value).normalize();
    }

    private Path parseSimpleDirectory(String[] args, String usage) {
        if (args.length == 3) {
            return workingDirectory;
        }
        if (args.length == 4) {
            return resolveDirectory(args[3]);
        }
        if (args.length == 5 && args[3].equals("--dir")) {
            return resolveDirectory(args[4]);
        }
        throw new CGenException(usage);
    }

    private static void usage(PrintStream stream) {
        stream.println("""
                CGen - YAML-driven C interface and module generator

                Usage:
                  CGen init [directory] [-f|--force]
                  CGen create interface <name> [directory]
                  CGen create module <name> [directory] [--implements <interface>[,<interface>...]]
                  CGen create state-machine <name> [directory]
                  CGen create observer <name> --interface <interface> [directory] [--capacity <n>]
                  CGen create command-table <name> [directory]
                  CGen create status-codes <name> [directory]
                  CGen create adapter <name> --from <interface> --to <interface> [directory]
                  CGen gen | generate [directory] [-f|--force] [-v|--verbose] [--also-nested]
                  CGen rename module <old-name> <new-name>
                  CGen detach

                -f, --force
                  init: overwrite an existing cgen.yaml instead of refusing.
                  generate: overwrite files on disk that aren't CGen-generated instead of refusing.

                -v, --verbose
                  generate: print the project root, scope, and for every output file which
                  spec produced it, whether it's new or was regenerated, and how many user
                  regions were carried over - instead of the progress bar.

                --also-nested
                  generate: also generate every nested project found under the scanned
                  directory (any subdirectory with its own cgen.yaml, normally left alone),
                  each using its own cgen.yaml settings - not the outer project's. Works even
                  when the starting directory has no cgen.yaml of its own; it's then used only
                  as a search root. First does a fast multithreaded directory count (live
                  progress, no cgen.yaml checking yet) and asks for confirmation with that
                  count; only once confirmed does the slower real scan run - checking each
                  directory for a cgen.yaml, live progress again - before generating anything.
                """);
    }
}
