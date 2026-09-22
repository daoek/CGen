package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hand-edit to generated code *outside* any usercode region must be refused (not silently
 * overwritten) unless -f/--force is passed - and every orphaned region must be visible in the
 * run's own console output, not only inside the file it's written back into. See TagHelper's
 * skeleton-hash mechanism and PinfitGenerator.reportOrphanedRegions.
 */
class NonRegionEditProtectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesAnEditOutsideAnyUsercodeRegionUnlessForced() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path header = temporaryDirectory.resolve("sensor_I.h");
        String generated = Files.readString(header);
        // Nothing here is inside a usercode region - a generated doc comment line.
        String tampered = generated.replaceFirst("@brief Portable sensor interface", "@brief HAND-EDITED sensor interface");
        assertTrue(!tampered.equals(generated), "test setup sanity check");
        Files.writeString(header, tampered);

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("sensor_I.h"), cli.errors());
        assertTrue(cli.errors().contains("edited outside its usercode regions"), cli.errors());
        assertEquals(tampered, Files.readString(header), "a refused generate must not touch the file");

        assertEquals(0, cli.run("generate", "-f"));
        assertFalse(Files.readString(header).contains("HAND-EDITED"), "force must overwrite it");
    }

    @Test
    void editingInsideAUsercodeRegionIsNeverRefused() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("create", "module", "sensor_impl", "--implements", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("sensor_impl.c");
        String edited = Files.readString(source).replace(
                "/*@Pinfit usercode+ function.sensor.init.body*/\n",
                "/*@Pinfit usercode+ function.sensor.init.body*/\n    return 42;\n");
        Files.writeString(source, edited);

        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(Files.readString(source).contains("return 42;"));
    }

    @Test
    void aFileFromBeforeThisFeatureIsNotFlagged() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path header = temporaryDirectory.resolve("sensor_I.h");
        String withHash = Files.readString(header);
        // Simulate output from before the skeleton-hash line existed: drop just that one line.
        String withoutHashLine = withHash.replaceFirst("(?m)^/\\*@Pinfit\\(skeleton-hash:[0-9a-f]+\\)\\*/\\n", "");
        assertTrue(!withoutHashLine.equals(withHash), "test setup sanity check");
        Files.writeString(header, withoutHashLine);

        assertEquals(0, cli.run("generate"), cli.errors());
    }

    @Test
    void orphanedRegionsAreReportedOnTheConsoleNotOnlyWrittenIntoTheFile() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("create", "module", "sensor_impl", "--implements", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("sensor_impl.c");
        Files.writeString(source, Files.readString(source).replace(
                "/*@Pinfit usercode+ function.sensor.init.body*/\n",
                "/*@Pinfit usercode+ function.sensor.init.body*/\n    return 42;\n"));

        Path moduleYaml = temporaryDirectory.resolve("sensor_impl.module.yaml");
        Files.writeString(moduleYaml, Files.readString(moduleYaml).replace("implements:\n  - sensor", "implements: []"));

        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(cli.output().contains("orphaned user region"), cli.output());
        assertTrue(cli.output().contains("sensor_impl.c"), cli.output());
        assertTrue(cli.output().contains("function.sensor.init.body"), cli.output());
    }
}
