package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZDiagnosticTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void diagnosePlainGenerateFailure() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        System.out.println("DIAG java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        System.out.println("DIAG temporaryDirectory=" + temporaryDirectory);
        int initCode = cli.run("init");
        System.out.println("DIAG initCode=" + initCode + " initErrors=[" + cli.errors() + "]");
        Files.writeString(temporaryDirectory.resolve("logger.module.yaml"), """
                kind: module
                name: logger
                implements: []
                includes: []
                context: []
                variables: []
                """);
        int generateCode = cli.run("generate");
        System.out.println("DIAG generateCode=" + generateCode + " generateErrors=[" + cli.errors() + "]");
        assertEquals(0, 0);
    }
}
