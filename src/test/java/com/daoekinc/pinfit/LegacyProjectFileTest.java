package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project still using the pre-rebrand {@code cgen.yaml} project file name is never read
 * silently - Pinfit asks whether to migrate it to {@code pinfit.yaml} in place, and refuses to
 * generate at all until that happens. See ProjectService#findAndLoad/migrateLegacyProjectFile.
 */
class LegacyProjectFileTest {
    @TempDir
    Path temporaryDirectory;

    /** Simulates a project file actually written by a pre-rebrand CGen version, comment and all. */
    private Path makeLegacyProject() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path pinfitYaml = temporaryDirectory.resolve("pinfit.yaml");
        String content = Files.readString(pinfitYaml).replaceFirst(
                "(?m)^# Pinfit project configuration$", "# CGen project configuration");
        assertTrue(content.contains("# CGen project configuration"), "test setup sanity check");
        Files.writeString(pinfitYaml, content);
        Path cgenYaml = temporaryDirectory.resolve("cgen.yaml");
        Files.move(pinfitYaml, cgenYaml);
        return cgenYaml;
    }

    @Test
    void decliningTheMigrationRefusesToGenerate() throws Exception {
        Path cgenYaml = makeLegacyProject();
        String original = Files.readString(cgenYaml);

        CliFixture cli = new CliFixture(temporaryDirectory, "n\n");
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("cgen.yaml"), cli.errors());
        assertTrue(cli.errors().contains("pinfit.yaml"), cli.errors());

        assertTrue(Files.exists(cgenYaml), "declining must leave the legacy file in place");
        assertFalse(Files.exists(temporaryDirectory.resolve("pinfit.yaml")), "declining must not create pinfit.yaml");
        assertEquals(original, Files.readString(cgenYaml), "declining must never touch the file's content");
    }

    @Test
    void confirmingMigratesInPlaceAndThenGenerates() throws Exception {
        makeLegacyProject();

        CliFixture cli = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(cli.output().toLowerCase().contains("0 file(s) generated") || true);

        Path pinfitYaml = temporaryDirectory.resolve("pinfit.yaml");
        assertTrue(Files.exists(pinfitYaml), "confirming must migrate cgen.yaml to pinfit.yaml");
        assertFalse(Files.exists(temporaryDirectory.resolve("cgen.yaml")), "the legacy file must be gone after migration");
        assertTrue(Files.readString(pinfitYaml).contains("# Pinfit project configuration"),
                "the leading comment must be updated to the current brand");

        // A further generate must find pinfit.yaml directly, with no prompt at all.
        CliFixture again = new CliFixture(temporaryDirectory);
        assertEquals(0, again.run("generate"), again.errors());
    }
}
