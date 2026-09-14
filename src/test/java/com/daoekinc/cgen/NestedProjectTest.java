package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NestedProjectTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void outerGenerateSkipsSubfolderWithItsOwnProjectFile() throws Exception {
        CliFixture outer = new CliFixture(temporaryDirectory);
        assertEquals(0, outer.run("init"));
        assertEquals(0, outer.run("create", "interface", "outer_iic"));

        Path libDirectory = temporaryDirectory.resolve("Lib/importedlib");
        Files.createDirectories(libDirectory);
        CliFixture lib = new CliFixture(libDirectory);
        assertEquals(0, lib.run("init"));
        assertEquals(0, lib.run("create", "interface", "lib_iic"));
        assertEquals(0, lib.run("generate"));
        String libHeaderBeforeOuterGenerate = Files.readString(libDirectory.resolve("lib_iic_I.h"));

        assertEquals(0, outer.run("generate"));

        assertTrue(Files.exists(temporaryDirectory.resolve("outer_iic_I.h")));
        assertFalse(Files.exists(temporaryDirectory.resolve("lib_iic_I.h")),
                "outer generate must not reach into the nested project's output");
        assertEquals(libHeaderBeforeOuterGenerate, Files.readString(libDirectory.resolve("lib_iic_I.h")),
                "nested project's already-generated file must be untouched by the outer generate");
    }

    @Test
    void refusesToCreateIntoASubfolderThatOwnsAnotherProject() throws Exception {
        CliFixture outer = new CliFixture(temporaryDirectory);
        assertEquals(0, outer.run("init"));
        Path libDirectory = temporaryDirectory.resolve("Lib/importedlib");
        Files.createDirectories(libDirectory);
        assertEquals(0, new CliFixture(libDirectory).run("init"));

        assertEquals(1, outer.run("create", "interface", "sneaky", "Lib/importedlib"));
        assertTrue(outer.errors().contains("separate project"));
        assertFalse(Files.exists(libDirectory.resolve("sneaky.interface.yaml")));
    }

    @Test
    void nestedProjectStillGeneratesOnItsOwnFromInsideIt() throws Exception {
        CliFixture outer = new CliFixture(temporaryDirectory);
        assertEquals(0, outer.run("init"));
        Path libDirectory = temporaryDirectory.resolve("Lib/importedlib");
        Files.createDirectories(libDirectory);
        CliFixture lib = new CliFixture(libDirectory);
        assertEquals(0, lib.run("init"));
        assertEquals(0, lib.run("create", "interface", "lib_iic"));

        assertEquals(0, lib.run("generate"));
        assertTrue(Files.exists(libDirectory.resolve("lib_iic_I.h")));
    }
}
