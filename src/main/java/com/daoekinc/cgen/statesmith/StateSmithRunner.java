package com.daoekinc.cgen.statesmith;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.model.ProjectConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Invokes StateSmith's {@code ss.cli} as an external process. CGen never bundles or downloads it.
 *
 * <p>Verified against a real {@code ss.cli 0.22.2} install: its exit code is 0 even on total
 * failure (missing file, invalid TOML, invalid UML) - the only reliable success signal is the
 * text {@code StateSmith Runner - Finished normally.} in its combined stdout/stderr. This class
 * checks that text, never the exit code.
 */
public final class StateSmithRunner {
    private static final String SUCCESS_MARKER = "Finished normally.";

    /**
     * Runs {@code <command> --version} and checks the configured {@code stateSmith.version} is a
     * substring of the output (StateSmith prints e.g. {@code StateSmith.Cli 0.22.2+<hash>}, so an
     * exact match would break on every rebuild hash). Throws with install/version instructions on
     * a missing tool or a mismatch.
     */
    public void checkVersion(ProjectConfig project) {
        ProjectConfig.StateSmithSettings settings = project.stateSmith();
        if (settings.version() == null || settings.version().isBlank()) {
            throw new CGenException("cgen.yaml is missing stateSmith.version, required because this project "
                    + "has an 'engine: statesmith' state machine.",
                    "Add to cgen.yaml", "stateSmith:\n  command: " + settings.command() + "\n  version: 0.22.2");
        }
        ProcessResult result = execute(settings.command(), List.of("--version"), null);
        if (result == null) {
            throw new CGenException("Cannot run '" + settings.command() + "' - is StateSmith's CLI installed and on PATH?",
                    "Install StateSmith.Cli", "https://github.com/StateSmith/StateSmith/wiki/CLI:-download-or-install");
        }
        if (!result.output().contains(settings.version())) {
            throw new CGenException("Installed StateSmith CLI does not report version '" + settings.version()
                    + "' (configured in cgen.yaml's stateSmith.version):\n\n" + result.output().strip(),
                    "Fix it by", "installing StateSmith.Cli " + settings.version()
                            + ", or updating cgen.yaml's stateSmith.version to match what's installed.");
        }
    }

    /**
     * Runs {@code ss.cli run <plantumlFile>} with the plantuml file's own directory (folder B) as
     * the working directory, so StateSmith's default next-to-the-input output naming writes
     * {@code <name>_sm.c/.h} (and its simulator {@code .html}) there too.
     */
    public void run(Path plantumlFile, Path yamlFile, ProjectConfig project) {
        ProjectConfig.StateSmithSettings settings = project.stateSmith();
        Path directory = plantumlFile.getParent();
        String fileArgument = plantumlFile.getFileName().toString();
        ProcessResult result = execute(settings.command(), List.of("run", fileArgument, "--no-ask"), directory);
        if (result == null) {
            throw new CGenException("Cannot run '" + settings.command() + "' for " + yamlFile
                    + " - is StateSmith's CLI installed and on PATH?");
        }
        if (!result.output().contains(SUCCESS_MARKER)) {
            throw new CGenException("StateSmith failed generating from " + yamlFile + ":\n\n" + result.output().strip());
        }
    }

    private static ProcessResult execute(String command, List<String> arguments, Path workingDirectory) {
        ProcessResult result = start(command, arguments, workingDirectory);
        // Confirmed against a real install: ss.cli's own executable is literally "ss.cli.exe"
        // (the command name itself already contains a dot, so this can't check for "no dot"),
        // and Windows' CreateProcess (unlike a shell) does not append .exe for a bare command
        // name even when its directory is on PATH. Java's ProcessBuilder inherits that behavior,
        // so a plain `command: ss.cli` in cgen.yaml (the documented default) would otherwise
        // never resolve. Retry once with .exe appended before giving up.
        if (result == null && !command.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            result = start(command + ".exe", arguments, workingDirectory);
        }
        return result;
    }

    private static ProcessResult start(String command, List<String> arguments, Path workingDirectory) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        commandLine.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(commandLine).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return new ProcessResult(output);
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CGenException("Interrupted while running '" + command + "'", exception);
        }
    }

    private record ProcessResult(String output) {
    }
}
