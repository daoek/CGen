package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.statesmith.StateSmithRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code engine: statesmith} generate path against fake {@code ss.cli} scripts, so every
 * StateSmith outcome (success, failure output, missing or unreadable output files, version
 * mismatch, not installed) is covered without the real tool. {@link StateSmithIntegrationTest}
 * covers the real one.
 */
class StateSmithRunnerTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final String MARKER = "/*@Pinfit(file:state-machine-statesmith:door.state-machine.yaml)*/";

    @TempDir
    Path temporaryDirectory;

    private enum Behavior { OK, FAIL, NO_FILES, UNREADABLE }

    /** Writes a fake ss.cli that reports version 1.2.3 and then behaves as {@code behavior} on "run". */
    private Path fakeStateSmith(Behavior behavior) throws Exception {
        Path tools = Files.createDirectories(temporaryDirectory.resolve("tools"));
        Path unreadable = tools.resolve("unreadable.bin");
        Files.write(unreadable, new byte[] {(byte) 0xC3, (byte) 0x28});
        String runBody = switch (behavior) {
            case OK -> WINDOWS
                    ? "echo " + MARKER + "> \"%~n2.h\"\r\necho " + MARKER + "> \"%~n2.c\"\r\necho Finished normally.\r\n"
                    : "base=$(basename \"$2\" .plantuml)\necho '" + MARKER + "' > \"$base.h\"\necho '" + MARKER + "' > \"$base.c\"\necho Finished normally.\n";
            case FAIL -> "echo boom\n";
            case NO_FILES -> "echo Finished normally.\n";
            case UNREADABLE -> WINDOWS
                    ? "echo " + MARKER + "> \"%~n2.h\"\r\ncopy /y \"" + unreadable + "\" \"%~n2.c\" >nul\r\necho Finished normally.\r\n"
                    : "base=$(basename \"$2\" .plantuml)\necho '" + MARKER + "' > \"$base.h\"\ncp '" + unreadable + "' \"$base.c\"\necho Finished normally.\n";
        };
        Path script;
        if (WINDOWS) {
            script = tools.resolve("ss-" + behavior + ".cmd");
            Files.writeString(script, "@echo off\r\nif \"%1\"==\"--version\" goto version\r\n" + runBody.replace("\n", "\r\n").replace("\r\r", "\r")
                    + "exit /b 0\r\n:version\r\necho StateSmith.Cli 1.2.3+fake\r\n");
        } else {
            script = tools.resolve("ss-" + behavior);
            Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo 'StateSmith.Cli 1.2.3+fake'; exit 0; fi\n" + runBody);
            script.toFile().setExecutable(true);
        }
        return script;
    }

    private CliFixture projectUsing(String command, String version) throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        String settings = "\nstateSmith:\n  command: '" + command.replace("'", "''") + "'\n"
                + (version == null ? "" : "  version: " + version + "\n");
        Files.writeString(projectFile, Files.readString(projectFile) + settings);
        assertEquals(0, cli.run("create", "state-machine", "door", "--engine", "statesmith"));
        return cli;
    }

    @Test
    void successfulRunGeneratesAndReportsEveryFile() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.OK).toString(), "1.2.3");
        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(Files.readString(temporaryDirectory.resolve("door_sm/door_sm.h")).contains(MARKER));
        assertTrue(cli.output().contains("7 file(s) generated"), cli.output());

        CliFixture again = new CliFixture(temporaryDirectory);
        assertEquals(0, again.run("generate", "-v"), again.errors());
        assertTrue(again.output().contains("door_sm.plantuml (regenerated, no user regions found)"), again.output());
    }

    @Test
    void failureOutputIsReported() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.FAIL).toString(), "1.2.3");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("StateSmith failed generating from"), cli.errors());
        assertTrue(cli.errors().contains("boom"), cli.errors());
    }

    @Test
    void missingOutputFilesAreReported() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.NO_FILES).toString(), "1.2.3");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("StateSmith did not produce"), cli.errors());
    }

    @Test
    void unreadableOutputFileIsReportedAsMissingMarker() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.UNREADABLE).toString(), "1.2.3");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("StateSmith did not produce"), cli.errors());
    }

    @Test
    void versionMismatchIsReported() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.OK).toString(), "9.9.9");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("does not report version '9.9.9'"), cli.errors());
        assertTrue(cli.errors().contains("StateSmith.Cli 1.2.3+fake"), cli.errors());
    }

    @Test
    void missingVersionIsReported() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.OK).toString(), null);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("pinfit.yaml is missing stateSmith.version"), cli.errors());
    }

    @Test
    void blankVersionIsReported() throws Exception {
        CliFixture cli = projectUsing(fakeStateSmith(Behavior.OK).toString(), "' '");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("pinfit.yaml is missing stateSmith.version"), cli.errors());
    }

    @Test
    void notInstalledIsReported() throws Exception {
        CliFixture cli = projectUsing(temporaryDirectory.resolve("no-such-tool").toString(), "1.2.3");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("is StateSmith's CLI installed and on PATH?"), cli.errors());
    }

    @Test
    void runReportsAToolThatCannotStart() throws Exception {
        StateSmithRunner runner = new StateSmithRunner();
        Path plantuml = Files.createDirectories(temporaryDirectory.resolve("door_sm")).resolve("door_sm.plantuml");
        PinfitException error = assertThrows(PinfitException.class,
                () -> runner.run(plantuml, temporaryDirectory.resolve("door.state-machine.yaml"),
                        project(temporaryDirectory.resolve("missing.exe").toString())));
        assertTrue(error.getMessage().startsWith("Cannot run '"), error.getMessage());
        assertTrue(error.getMessage().contains("door.state-machine.yaml"), error.getMessage());
    }

    @Test
    void interruptionWhileWaitingIsReported() throws Exception {
        StateSmithRunner runner = new StateSmithRunner();
        ProjectConfig project = project(fakeStateSmith(Behavior.OK).toString());
        Thread.currentThread().interrupt();
        try {
            PinfitException error = assertThrows(PinfitException.class, () -> runner.checkVersion(project));
            assertTrue(error.getMessage().startsWith("Interrupted while running"), error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            Thread.interrupted();
        }
    }

    private ProjectConfig project(String command) {
        return new ProjectConfig(temporaryDirectory, "demo", "0.1.0", new ProjectConfig.Documentation("doxygen", null), 4, "\n",
                "extern", true, false, new ProjectConfig.StateSmithSettings(command, "1.2.3"), false);
    }
}
