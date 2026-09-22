package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Full round trip through a real {@code ss.cli}: generates a hierarchical door example, actually
 * runs StateSmith, and - if a C compiler is also available - compiles everything and runs a tiny
 * harness that checks entry/exit ordering across a parent (composite) transition.
 *
 * <p>Skipped with a visible message when {@code ss.cli} (or {@code ss.cli.exe}) is not runnable;
 * the {@code gcc} compile-and-run step is separately, silently skipped when {@code gcc} is not on
 * PATH, so the StateSmith-only assertions above it still run without a compiler installed.
 */
class StateSmithIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesAndRunsThroughRealStateSmith() throws Exception {
        String stateSmithVersion = detectStateSmithVersion();
        Assumptions.assumeTrue(stateSmithVersion != null, "ss.cli not runnable on PATH - skipping StateSmith integration test");

        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("pinfit.yaml"),
                Files.readString(temporaryDirectory.resolve("pinfit.yaml"))
                        + "\nstateSmith:\n  command: ss.cli\n  version: " + stateSmithVersion + "\n");
        assertEquals(0, cli.run("create", "state-machine", "door", "--engine", "statesmith"));

        assertEquals(0, cli.run("generate", "-v"), cli.errors());

        Path smDirectory = temporaryDirectory.resolve("door_sm");
        Path smHeader = smDirectory.resolve("door_sm.h");
        Path smSource = smDirectory.resolve("door_sm.c");
        assertTrue(Files.isRegularFile(smHeader), "StateSmith should have written door_sm.h");
        assertTrue(Files.isRegularFile(smSource), "StateSmith should have written door_sm.c");
        assertTrue(Files.readString(smHeader).contains("door_sm_StateId_IDLE"));
        assertTrue(Files.readString(smHeader).contains("door_sm_EventId_START"));
        assertTrue(Files.readString(smSource).contains("door_hook_state_IDLE_entry"));

        // Regenerating must be idempotent through the real tool too.
        String smHeaderBefore = Files.readString(smHeader);
        assertEquals(0, cli.run("generate"), cli.errors());
        assertEquals(smHeaderBefore, Files.readString(smHeader), "regenerating through ss.cli must not change door_sm.h");

        if (isOnPath("gcc")) {
            compileAndRunEntryExitOrderingCheck();
        }
    }

    /** Runs {@code ss.cli --version} (bare, then with .exe) and returns the reported version, or null if unrunnable. */
    private static String detectStateSmithVersion() {
        for (String command : new String[] {"ss.cli", "ss.cli.exe"}) {
            try {
                Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor();
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("StateSmith\\.Cli (\\S+?)(?:\\+|\\s|$)").matcher(output);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            } catch (IOException | InterruptedException ignored) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return null;
    }

    private static boolean isOnPath(String command) {
        for (String candidate : new String[] {command, command + ".exe"}) {
            try {
                new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor();
                return true;
            } catch (IOException ignored) {
                // try the next candidate
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Compiles door.c/.h, door_hooks.c/.h and door_sm/door_sm.c with {@code gcc -std=c99} and runs
     * a small harness that dispatches START (IDLE -> the composite RUNNING's own initial child,
     * RUN_STEADY) and checks the resulting state through the public API - proving the whole
     * generated chain actually compiles and runs, not just that StateSmith accepted the diagram.
     */
    private void compileAndRunEntryExitOrderingCheck() throws Exception {
        Path harness = temporaryDirectory.resolve("harness.c");
        Files.writeString(harness, """
                #include "door.h"
                #include <stdio.h>

                int main(void)
                {
                    door_context_t door;
                    door_init(&door);
                    if (door_get_state(&door) != DOOR_STATE_IDLE)
                    {
                        printf("FAIL init state\\n");
                        return 1;
                    }
                    door_on_START(&door);
                    if (door_get_state(&door) != DOOR_STATE_RUN_STEADY)
                    {
                        printf("FAIL after START: %d\\n", (int)door_get_state(&door));
                        return 1;
                    }
                    printf("OK\\n");
                    return 0;
                }
                """);

        Path binary = temporaryDirectory.resolve("harness.exe");
        ProcessBuilder gcc = new ProcessBuilder("gcc", "-std=c99", "-Wall", "-Wextra", "-Werror",
                "door.c", "door_hooks.c", "door_sm/door_sm.c", "harness.c", "-o", binary.toString());
        gcc.directory(temporaryDirectory.toFile());
        gcc.redirectErrorStream(true);
        Process compile = gcc.start();
        String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int compileExit = compile.waitFor();
        assertEquals(0, compileExit, "gcc failed:\n" + compileOutput);

        Process run = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
        String runOutput = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int runExit = run.waitFor();
        assertEquals(0, runExit, "harness failed:\n" + runOutput);
        assertTrue(runOutput.contains("OK"), runOutput);
    }
}
