package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesToOverwriteExistingFilesOrProject() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(1, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "clock"));
        Path header = temporaryDirectory.resolve("clock_I.h");
        Files.writeString(header, "user owned\n");
        assertEquals(1, cli.run("generate"));
        assertEquals("user owned\n", Files.readString(header));
    }

    @Test
    void reportsParameterYamlSyntax() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("broken.interface.yaml"), """
                kind: interface
                name: common_iic
                functions:
                  - name: SendData
                    return: int
                    parameters: [uint8_t buffer, uint32_t len]
                """);

        assertEquals(1, cli.run("generate"));
        String error = cli.errors();
        assertTrue(error.contains("broken.interface.yaml.functions[0].parameters[0]"));
        assertTrue(error.contains("\u001B[1;31mCGen error\u001B[0m"));
        assertTrue(error.contains("\u001B[1;36mExample YAML\u001B[0m"));
        assertTrue(error.contains("""
                parameters:
                  - { type: uint8_t *, name: buffer }
                  - { type: uint32_t, name: len }"""));
    }

    @Test
    void requiresExplicitErrorValueForNonVoidFunction() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("status.interface.yaml"), """
                kind: interface
                name: status
                functions:
                  - name: read
                    return: uint32_t
                    parameters: []
                """);

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("invalidReturn is required for non-void function 'read'"));
    }
}
