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

    @Test
    void alsoNestedGeneratesNestedProjectsUsingTheirOwnSettings() throws Exception {
        CliFixture outer = new CliFixture(temporaryDirectory);
        assertEquals(0, outer.run("init"));
        Files.writeString(temporaryDirectory.resolve("outer.module.yaml"), """
                kind: module
                name: outer
                implements: []
                includes: []
                context: []
                variables: []
                functions:
                  - name: go_to_state
                    return: void
                    parameters: []
                    visibility: public
                """);

        Path libDirectory = temporaryDirectory.resolve("Lib/importedlib");
        Files.createDirectories(libDirectory);
        CliFixture lib = new CliFixture(libDirectory);
        assertEquals(0, lib.run("init"));
        Path libProjectFile = libDirectory.resolve("cgen.yaml");
        Files.writeString(libProjectFile, Files.readString(libProjectFile).replace(
                "# functionNaming: camelCase # snake_case (default) or camelCase for generated function names",
                "functionNaming: camelCase"));
        Files.writeString(libDirectory.resolve("libmod.module.yaml"), """
                kind: module
                name: libmod
                implements: []
                includes: []
                context: []
                variables: []
                functions:
                  - name: go_to_state
                    return: void
                    parameters: []
                    visibility: public
                """);

        assertEquals(0, outer.run("generate"));
        assertFalse(Files.exists(libDirectory.resolve("libmod.h")), "plain generate must not reach the nested project");

        assertEquals(0, outer.run("generate", "--also-nested"));
        assertTrue(Files.exists(libDirectory.resolve("libmod.h")), "--also-nested must generate the nested project");
        String outerHeader = Files.readString(temporaryDirectory.resolve("outer.h"));
        String libHeader = Files.readString(libDirectory.resolve("libmod.h"));
        assertTrue(outerHeader.contains("void go_to_state(void);"), "outer project uses its own snake_case default");
        assertTrue(libHeader.contains("void goToState(void);"), "nested project uses its own camelCase setting");
    }
}
