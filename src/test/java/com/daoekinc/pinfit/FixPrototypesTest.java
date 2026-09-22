package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixPrototypesTest {
    @TempDir
    Path temporaryDirectory;

    private Path generateFooModule() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("foo.module.yaml"), """
                kind: module
                name: foo
                implements: []
                includes: []
                context: []
                variables: []
                functions:
                  - name: do_thing
                    return: void
                    parameters: []
                    visibility: public
                """);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("foo.c");
        String generated = Files.readString(source);
        String withHelper = generated.replace(
                "/*@Pinfit usercode+ module.source.footer*/\n/*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ module.source.footer*/\n"
                        + "static void helper_function(int x)\n"
                        + "{\n"
                        + "    (void)x;\n"
                        + "}\n"
                        + "/*@Pinfit usercode-*/");
        assertTrue(!withHelper.equals(generated), "expected a module.source.footer region to inject into");
        Files.writeString(source, withHelper);
        return source;
    }

    @Test
    void acceptingTheDiffAddsTheMissingPrototype() throws Exception {
        Path source = generateFooModule();

        CliFixture cli = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, cli.run("fix-prototypes"));

        assertTrue(cli.output().contains("helper_function"));
        assertTrue(cli.output().contains("+static void helper_function(int x);"));
        assertTrue(cli.output().contains("1 prototype(s) added in 1 file(s)"));

        String updated = Files.readString(source);
        assertTrue(updated.contains("""
                /*@Pinfit usercode+ module.source.prototypes*/
                static void helper_function(int x);
                /*@Pinfit usercode-*/"""));
    }

    @Test
    void emptyParameterListGetsVoidInThePrototype() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("foo.module.yaml"), """
                kind: module
                name: foo
                implements: []
                includes: []
                context: []
                variables: []
                functions:
                  - name: do_thing
                    return: void
                    parameters: []
                    visibility: public
                """);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("foo.c");
        String generated = Files.readString(source);
        String withHelper = generated.replace(
                "/*@Pinfit usercode+ module.source.footer*/\n/*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ module.source.footer*/\n"
                        + "static void helper_function()\n"
                        + "{\n"
                        + "}\n"
                        + "/*@Pinfit usercode-*/");
        assertTrue(!withHelper.equals(generated), "expected a module.source.footer region to inject into");
        Files.writeString(source, withHelper);

        CliFixture fix = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, fix.run("fix-prototypes"));
        assertTrue(fix.output().contains("+static void helper_function(void);"));

        String updated = Files.readString(source);
        assertTrue(updated.contains("""
                /*@Pinfit usercode+ module.source.prototypes*/
                static void helper_function(void);
                /*@Pinfit usercode-*/"""));
    }

    @Test
    void decliningTheDiffLeavesTheFileUnchanged() throws Exception {
        Path source = generateFooModule();
        String before = Files.readString(source);

        CliFixture cli = new CliFixture(temporaryDirectory, "n\n");
        assertEquals(0, cli.run("fix-prototypes"));

        assertEquals("0 prototype(s) added in 0 file(s)", cli.output().strip().lines()
                .reduce((first, second) -> second).orElse(""));
        assertEquals(before, Files.readString(source));
    }

    @Test
    void secondRunFindsNothingLeftToFix() throws Exception {
        generateFooModule();
        assertEquals(0, new CliFixture(temporaryDirectory, "y\n").run("fix-prototypes"));

        CliFixture second = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, second.run("fix-prototypes"));
        assertFalse(second.output().contains("helper_function"));
        assertTrue(second.output().contains("0 prototype(s) added in 0 file(s)"));
    }
}
