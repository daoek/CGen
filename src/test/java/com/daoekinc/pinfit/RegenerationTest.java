package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegenerationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void removesUntouchedFunctionRegionsWhenModuleStopsImplementingInterface() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "common_iic"));
        assertEquals(0, cli.run("create", "module", "ra_iic_master", "--implements", "common_iic"));
        assertEquals(0, cli.run("generate"));

        Path moduleYaml = temporaryDirectory.resolve("ra_iic_master.module.yaml");
        Files.writeString(moduleYaml, Files.readString(moduleYaml)
                .replace("implements:\n  - common_iic", "implements: []"));
        assertEquals(0, cli.run("generate"));

        String source = Files.readString(temporaryDirectory.resolve("ra_iic_master.c"));
        assertFalse(source.contains("common_iic"));
        assertFalse(source.contains("orphaned-user-region"));
        assertFalse(source.contains("return -1;"));
    }
}
