package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A cheap syntax-only regression guard for the POSIX install scripts, gated on {@code sh} being
 * available (skips on a Windows CI runner with no POSIX shell). This does not exercise install.sh
 * end-to-end - that needs a packaged jar (install.sh's --skip-build path expects one in target/,
 * which does not exist yet during `mvn test`, before the package phase) and network access for
 * the --version download path, both out of place in a unit test. Both were verified by hand
 * end-to-end during development: local-build install, launching the installed CGen, running the
 * installed uninstall.sh with no argument (self-locating), and a standalone copy of uninstall.sh
 * elsewhere correctly refusing (no marker file) rather than removing the wrong directory.
 */
class InstallScriptsTest {
    @Test
    void installAndUninstallScriptsAreSyntacticallyValid() throws Exception {
        Assumptions.assumeTrue(isShAvailable(), "no POSIX 'sh' on PATH - skipping install script syntax check");

        Path repoRoot = Path.of("").toAbsolutePath();
        assertSyntaxOk(repoRoot.resolve("scripts/install.sh"));
        assertSyntaxOk(repoRoot.resolve("scripts/uninstall.sh"));
        assertSyntaxOk(repoRoot.resolve("scripts/CGen.sh"));
    }

    private static void assertSyntaxOk(Path script) throws Exception {
        Process process = new ProcessBuilder("sh", "-n", script.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        assertEquals(0, exit, script + " failed 'sh -n':\n" + output);
    }

    private static boolean isShAvailable() {
        try {
            return new ProcessBuilder("sh", "-c", "true").start().waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
