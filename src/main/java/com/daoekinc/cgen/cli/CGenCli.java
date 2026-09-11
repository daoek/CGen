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
        if (args.length > 2) {
            throw new CGenException("Usage: CGen init [directory]");
        }
        Path directory = args.length == 2 ? workingDirectory.resolve(args[1]).normalize() : workingDirectory;
        Path file = projects.init(directory);
        out.println("Created " + file);
        return 0;
    }

    private int create(String[] args) {
        if (args.length < 3) {
            throw new CGenException("Usage: CGen create <interface|module|state-machine|observer|command-table|status-codes|adapter> <name> [directory] [...]");
        }
        ProjectConfig project = projects.findAndLoad(workingDirectory);
        if (args[1].equals("interface")) {
            Path directory;
            if (args.length == 3) {
                directory = workingDirectory;
            } else if (args.length == 4) {
                directory = resolveDirectory(args[3]);
            } else if (args.length == 5 && args[3].equals("--dir")) {
                directory = resolveDirectory(args[4]);
            } else {
                throw new CGenException("Usage: CGen create interface <name> [directory]");
            }
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
            Path directory;
            if (args.length == 3) {
                directory = workingDirectory;
            } else if (args.length == 4) {
                directory = resolveDirectory(args[3]);
            } else if (args.length == 5 && args[3].equals("--dir")) {
                directory = resolveDirectory(args[4]);
            } else {
                throw new CGenException("Usage: CGen create state-machine <name> [directory]");
            }
            out.println("Created " + projects.createStateMachine(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("command-table")) {
            Path directory;
            if (args.length == 3) {
                directory = workingDirectory;
            } else if (args.length == 4) {
                directory = resolveDirectory(args[3]);
            } else if (args.length == 5 && args[3].equals("--dir")) {
                directory = resolveDirectory(args[4]);
            } else {
                throw new CGenException("Usage: CGen create command-table <name> [directory]");
            }
            out.println("Created " + projects.createCommandTable(project, args[2], directory));
            return 0;
        }
        if (args[1].equals("status-codes")) {
            Path directory;
            if (args.length == 3) {
                directory = workingDirectory;
            } else if (args.length == 4) {
                directory = resolveDirectory(args[3]);
            } else if (args.length == 5 && args[3].equals("--dir")) {
                directory = resolveDirectory(args[4]);
            } else {
                throw new CGenException("Usage: CGen create status-codes <name> [directory]");
            }
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
        if (args.length > 2) {
            throw new CGenException("Usage: CGen generate [directory]");
        }
        ProjectConfig project = projects.findAndLoad(workingDirectory);
        Path scope = projects.existingDirectory(project, args.length == 2 ? resolveDirectory(args[1]) : workingDirectory);
        List<Path> files = generator.generate(project, scope,
                (completed, total, path) -> printProgress(completed, total, project.root().relativize(path)));
        if (!files.isEmpty()) {
            out.println();
        }
        out.println(files.size() + " file(s) generated");
        return 0;
    }

    private void printProgress(int completed, int total, Path relativePath) {
        int width = 30;
        int filled = (int) Math.round((completed / (double) total) * width);
        String bar = GREEN + "#".repeat(filled) + RESET + "-".repeat(width - filled);
        out.print("\r[" + bar + "] " + completed + "/" + total + "  " + relativePath + "[K");
        out.flush();
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

    private static void usage(PrintStream stream) {
        stream.println("""
                CGen - YAML-driven C interface and module generator

                Usage:
                  CGen init [directory]
                  CGen create interface <name> [directory]
                  CGen create module <name> [directory] [--implements <interface>[,<interface>...]]
                  CGen create state-machine <name> [directory]
                  CGen create observer <name> --interface <interface> [directory] [--capacity <n>]
                  CGen create command-table <name> [directory]
                  CGen create status-codes <name> [directory]
                  CGen create adapter <name> --from <interface> --to <interface> [directory]
                  CGen gen | generate [directory]
                  CGen detach
                """);
    }
}
