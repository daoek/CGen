package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenameTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void renamesModuleFilesAndCarriesUserCodeForward() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "common_iic"));
        assertEquals(0, cli.run("create", "module", "ra_iic_master_temp", "--implements", "common_iic"));
        assertEquals(0, cli.run("generate"));

        Path oldSource = temporaryDirectory.resolve("ra_iic_master_temp.c");
        String withUserCode = Files.readString(oldSource).replace(
                "/*@CGen usercode+ module.source.includes*/",
                "/*@CGen usercode+ module.source.includes*/\n#include \"vendor_i2c.h\"");
        Files.writeString(oldSource, withUserCode);

        assertEquals(0, cli.run("rename", "module", "ra_iic_master_temp", "ra_iic_master"));

        assertFalse(Files.exists(temporaryDirectory.resolve("ra_iic_master_temp.h")));
        assertFalse(Files.exists(temporaryDirectory.resolve("ra_iic_master_temp.c")));
        assertFalse(Files.exists(temporaryDirectory.resolve("ra_iic_master_temp.module.yaml")));

        String newSpec = Files.readString(temporaryDirectory.resolve("ra_iic_master.module.yaml"));
        assertTrue(newSpec.contains("name: ra_iic_master\n"));
        assertTrue(newSpec.contains("header: ra_iic_master.h"));
        assertTrue(newSpec.contains("source: ra_iic_master.c"));

        String newSource = Files.readString(temporaryDirectory.resolve("ra_iic_master.c"));
        assertTrue(newSource.contains("#include \"vendor_i2c.h\""));

        String newHeader = Files.readString(temporaryDirectory.resolve("ra_iic_master.h"));
        assertTrue(newHeader.contains("ra_iic_master_context_t") || !newHeader.contains("ra_iic_master_temp"));
    }

    @Test
    void refusesUnknownModuleName() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(1, cli.run("rename", "module", "does_not_exist", "new_name"));
        assertTrue(cli.errors().contains("No module named 'does_not_exist'"));
    }
}
