package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Argument parsing, help and usage errors of every CLI command. */
class CliCommandTest {
    @TempDir
    Path temporaryDirectory;

    private CliFixture initialized() {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        return cli;
    }

    private void assertUsageError(CliFixture cli, String expected, String... args) {
        assertEquals(1, cli.run(args), String.join(" ", args));
        assertTrue(cli.errors().contains(expected), cli.errors());
    }

    // ---- help ----

    @Test
    void noArgumentsPrintsUsage() {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run());
        assertTrue(cli.output().contains("Usage: pinfit <command> [options]"));
        assertTrue(cli.output().contains("fix-prototypes"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"help", "--help", "-h"})
    void helpCommandPrintsUsage(String helpCommand) {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run(helpCommand));
        assertTrue(cli.output().contains("Commands:"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"init", "create", "gen", "generate", "rename", "fix-prototypes", "detach"})
    void helpForEveryCommand(String command) {
        CliFixture viaHelp = new CliFixture(temporaryDirectory);
        assertEquals(0, viaHelp.run("help", command));
        assertTrue(viaHelp.output().contains("Usage:"), viaHelp.output());

        CliFixture viaFlag = new CliFixture(temporaryDirectory);
        assertEquals(0, viaFlag.run(command, "extra", "-h"));
        assertEquals(0, new CliFixture(temporaryDirectory).run(command, "--help"));
        assertEquals(viaHelp.output(), viaFlag.output());
    }

    @Test
    void helpForUnknownCommandFails() {
        assertUsageError(new CliFixture(temporaryDirectory), "Unknown command 'bogus'", "help", "bogus");
    }

    @Test
    void unknownCommandFails() {
        assertUsageError(new CliFixture(temporaryDirectory), "Unknown command 'bogus'", "bogus");
    }

    @Test
    void mainEntryPointRunsHelp() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            new App();
            App.main(new String[] {"help"});
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Usage: pinfit <command> [options]"));
    }

    // ---- init ----

    @Test
    void initIntoDirectoryAndForceOverwrite() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init", "sub"));
        Path file = temporaryDirectory.resolve("sub/pinfit.yaml");
        assertTrue(Files.exists(file));
        Files.writeString(file, "changed\n");
        assertEquals(0, cli.run("init", "sub", "--force"));
        assertTrue(Files.readString(file).contains("name: 'sub'"));
        assertEquals(0, cli.run("init", "-f", "sub"));
    }

    @Test
    void initRejectsTwoDirectories() {
        assertUsageError(new CliFixture(temporaryDirectory), "Usage: pinfit init", "init", "a", "b");
    }

    // ---- create ----

    @Test
    void createNeedsTypeAndName() {
        assertUsageError(initialized(), "Usage: pinfit create <interface", "create", "interface");
    }

    @Test
    void createRejectsUnknownType() {
        assertUsageError(initialized(), "Create type must be interface, module", "create", "widget", "w");
    }

    @Test
    void createSimpleKindsAcceptPositionalOrFlagDirectory() {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "interface", "i1", "a"));
        assertEquals(0, cli.run("create", "interface", "i2", "--dir", "b"));
        assertEquals(0, cli.run("create", "command-table", "t1", "a"));
        assertEquals(0, cli.run("create", "status-codes", "s1", "--dir", "b"));
        assertTrue(Files.exists(temporaryDirectory.resolve("a/i1.interface.yaml")));
        assertTrue(Files.exists(temporaryDirectory.resolve("b/i2.interface.yaml")));
        assertTrue(Files.exists(temporaryDirectory.resolve("a/t1.command-table.yaml")));
        assertTrue(Files.exists(temporaryDirectory.resolve("b/s1.status-codes.yaml")));
        assertUsageError(cli, "Usage: pinfit create interface <name> [directory]", "create", "interface", "i3", "a", "b");
        assertUsageError(cli, "Usage: pinfit create interface <name> [directory]", "create", "interface", "i3", "--x", "b");
        assertUsageError(cli, "Usage: pinfit create interface <name> [directory]", "create", "interface", "i3", "a", "b", "c");
    }

    @Test
    void createModuleArguments() throws Exception {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "module", "m1", "--dir", "mods", "--implements", "a, ,b"));
        assertTrue(Files.readString(temporaryDirectory.resolve("mods/m1.module.yaml")).contains("implements:\n  - a\n  - b"));
        assertEquals(0, cli.run("create", "module", "m2", "mods"));
        assertUsageError(cli, "Usage: pinfit create module", "create", "module", "m3", "mods", "other");
        assertUsageError(cli, "Usage: pinfit create module", "create", "module", "m3", "--dir", "a", "--dir", "b");
        assertUsageError(cli, "Usage: pinfit create module", "create", "module", "m3", "--implements");
        assertUsageError(cli, "Usage: pinfit create module", "create", "module", "m3", "--dir");
    }

    @Test
    void createStateMachineArguments() throws Exception {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "state-machine", "sm1", "--dir", "machines", "--engine", "statesmith"));
        assertTrue(Files.readString(temporaryDirectory.resolve("machines/sm1.state-machine.yaml")).contains("engine: statesmith"));
        assertEquals(0, cli.run("create", "state-machine", "sm2", "machines"));
        assertUsageError(cli, "--engine must be builtin or statesmith, got 'fancy'", "create", "state-machine", "sm3", "--engine", "fancy");
        assertUsageError(cli, "Usage: pinfit create state-machine", "create", "state-machine", "sm3", "a", "b");
        assertUsageError(cli, "Usage: pinfit create state-machine", "create", "state-machine", "sm3", "--dir", "a", "--dir", "b");
        assertUsageError(cli, "Usage: pinfit create state-machine", "create", "state-machine", "sm3", "--engine");
        assertUsageError(cli, "Usage: pinfit create state-machine", "create", "state-machine", "sm3", "--dir");
    }

    @Test
    void createObserverArguments() throws Exception {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "observer", "o1", "--interface", "events", "--capacity", "4", "--dir", "obs"));
        assertTrue(Files.readString(temporaryDirectory.resolve("obs/o1.observer.yaml")).contains("capacity: 4"));
        assertEquals(0, cli.run("create", "observer", "o2", "obs", "--interface", "events"));
        assertUsageError(cli, "--capacity must be an integer", "create", "observer", "o3", "--interface", "e", "--capacity", "many");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "obs");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "a", "b");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "--dir", "a", "--dir", "b");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "--capacity");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "--interface");
        assertUsageError(cli, "Usage: pinfit create observer", "create", "observer", "o3", "--dir");
    }

    @Test
    void createAdapterArguments() throws Exception {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "adapter", "a1", "--from", "x", "--to", "y", "--dir", "adapters"));
        assertTrue(Files.exists(temporaryDirectory.resolve("adapters/a1.adapter.yaml")));
        assertEquals(0, cli.run("create", "adapter", "a2", "adapters", "--from", "x", "--to", "y"));
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--from", "x");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--to", "y");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "a", "b");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--dir", "a", "--dir", "b");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--from");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--to");
        assertUsageError(cli, "Usage: pinfit create adapter", "create", "adapter", "a3", "--dir");
    }

    // ---- generate ----

    @Test
    void generateRejectsTwoDirectories() {
        assertUsageError(initialized(), "Usage: pinfit generate", "generate", "a", "b");
    }

    @Test
    void generateVerboseReportsNewAndRegeneratedFiles() throws Exception {
        CliFixture cli = initialized();
        assertEquals(0, cli.run("create", "module", "m", "src"));
        assertEquals(0, cli.run("generate", "src", "--verbose", "--strict"));
        assertTrue(cli.output().contains("(new file)"), cli.output());

        Path source = temporaryDirectory.resolve("src/m.c");
        Files.writeString(source, Files.readString(source).replace(
                "/*@Pinfit usercode+ module.source.footer*/\n", "/*@Pinfit usercode+ module.source.footer*/\n/* kept */\n"));
        CliFixture again = new CliFixture(temporaryDirectory);
        assertEquals(0, again.run("gen", "-v", "--force"));
        assertTrue(again.output().contains("user region(s) carried over"), again.output());
    }

    @Test
    void alsoNestedWithoutProjectRequiresAnExistingDirectory() {
        CliFixture cli = new CliFixture(temporaryDirectory, "y\n");
        assertUsageError(cli, "Directory does not exist:", "generate", "missing", "--also-nested");
    }

    @Test
    void alsoNestedWithoutProjectVerboseAndStrict() throws Exception {
        Path lib = temporaryDirectory.resolve("lib");
        Files.createDirectories(lib);
        CliFixture libCli = new CliFixture(lib);
        assertEquals(0, libCli.run("init"));
        assertEquals(0, libCli.run("create", "interface", "lib_if"));

        CliFixture cli = new CliFixture(temporaryDirectory, "yes\n");
        assertEquals(0, cli.run("generate", "--also-nested", "--verbose", "--strict"));
        assertTrue(cli.output().contains("No pinfit.yaml found at or above"), cli.output());
        assertTrue(cli.output().contains("Nested project: "), cli.output());
        assertTrue(cli.output().contains(" under "), cli.output());
        assertTrue(Files.exists(lib.resolve("lib_if_I.h")));
    }

    @Test
    void alsoNestedWithNothingFoundGeneratesNothing() {
        CliFixture cli = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, cli.run("generate", "--also-nested"));
        assertTrue(cli.output().contains("Found 0 nested projects."), cli.output());
        assertTrue(cli.output().contains("0 file(s) generated"), cli.output());
    }

    @Test
    void alsoNestedWithClosedInputFails() {
        CliFixture cli = new CliFixture(temporaryDirectory, CliFixture.failingInput());
        assertUsageError(cli, "Cannot read confirmation: stdin closed", "generate", "--also-nested");
    }

    @Test
    void alsoNestedWithNoAnswerCancels() {
        CliFixture cli = new CliFixture(temporaryDirectory, "");
        assertEquals(1, cli.run("generate", "--also-nested"));
        assertTrue(cli.output().contains("Cancelled."));
    }

    // ---- rename / detach / fix-prototypes ----

    @Test
    void renameUsage() {
        CliFixture cli = initialized();
        assertUsageError(cli, "Usage: pinfit rename module", "rename", "module", "a");
        assertUsageError(cli, "Usage: pinfit rename module", "rename", "interface", "a", "b");
    }

    @Test
    void detachUsageAndClosedInput() {
        assertUsageError(initialized(), "Usage: pinfit detach", "detach", "now");
        CliFixture closed = new CliFixture(temporaryDirectory, CliFixture.failingInput());
        assertUsageError(closed, "Cannot read detach confirmation: stdin closed", "detach");
        assertTrue(Files.exists(temporaryDirectory.resolve("pinfit.yaml")));
    }

    @Test
    void fixPrototypesArguments() throws Exception {
        CliFixture cli = initialized();
        Files.createDirectories(temporaryDirectory.resolve("src"));
        assertEquals(0, cli.run("fix-prototypes", "src"));
        assertTrue(cli.output().contains("0 prototype(s) added in 0 file(s)"));
        assertUsageError(cli, "Usage: pinfit fix-prototypes [directory]", "fix-prototypes", "a", "b");
    }

    @Test
    void legacyMigrationWithClosedInputFails() throws Exception {
        initialized();
        Files.move(temporaryDirectory.resolve("pinfit.yaml"), temporaryDirectory.resolve("cgen.yaml"));
        CliFixture cli = new CliFixture(temporaryDirectory, CliFixture.failingInput());
        assertUsageError(cli, "Cannot read confirmation: stdin closed", "generate");
        assertFalse(Files.exists(temporaryDirectory.resolve("pinfit.yaml")));
    }
}
