package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every interactive prompt answered with "yes", with nothing (EOF), and with unreadable input. */
class CliPromptTest {
    @TempDir
    Path temporaryDirectory;

    // ---- @PinfitSwitch external enum confirmation ----

    private Path moduleWithExternalEnumSwitch() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("external.h"), """
                typedef enum
                {
                    FAULT_A,
                    FAULT_B
                } fault_t;
                """);
        Files.writeString(temporaryDirectory.resolve("motor.module.yaml"), """
                kind: module
                name: motor
                includes: ['"external.h"']
                functions:
                  - { name: handle, parameters: [fault_t fault], visibility: public }
                """);
        assertEquals(0, cli.run("generate"));
        Path source = temporaryDirectory.resolve("motor.c");
        Files.writeString(source, Files.readString(source).replace(
                "/*@Pinfit usercode+ function.handle.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.handle.body*/\n    /*@PinfitSwitch fault_t*/\n    switch (fault)\n    {\n    }\n"
                        + "    /*@Pinfit usercode-*/"));
        return source;
    }

    @Test
    void switchEnumConfirmedWithYes() throws Exception {
        Path source = moduleWithExternalEnumSwitch();
        CliFixture cli = new CliFixture(temporaryDirectory, "YES\n");
        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(Files.readString(source).contains("case FAULT_B:"));
    }

    @Test
    void switchEnumDeclinedByEndOfInput() throws Exception {
        moduleWithExternalEnumSwitch();
        assertEquals(1, new CliFixture(temporaryDirectory, "").run("generate"));
    }

    @Test
    void switchEnumWithUnreadableInput() throws Exception {
        moduleWithExternalEnumSwitch();
        CliFixture cli = new CliFixture(temporaryDirectory, CliFixture.failingInput());
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("Cannot read confirmation: stdin closed"), cli.errors());
    }

    // ---- legacy cgen.yaml migration ----

    @ParameterizedTest
    @ValueSource(strings = {"yes\n", ""})
    void legacyMigrationAnswers(String answer) throws Exception {
        assertEquals(0, new CliFixture(temporaryDirectory).run("init"));
        Files.move(temporaryDirectory.resolve("pinfit.yaml"), temporaryDirectory.resolve("cgen.yaml"));
        int exit = new CliFixture(temporaryDirectory, answer).run("generate");
        boolean confirmed = !answer.isEmpty();
        assertEquals(confirmed ? 0 : 1, exit);
        assertEquals(confirmed, Files.exists(temporaryDirectory.resolve("pinfit.yaml")));
    }

    // ---- fix-prototypes ----

    private Path moduleWithUnprototypedHelper() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("foo.module.yaml"), "kind: module\nname: foo\n");
        assertEquals(0, cli.run("generate"));
        Path source = temporaryDirectory.resolve("foo.c");
        Files.writeString(source, Files.readString(source).replace(
                "/*@Pinfit usercode+ module.source.footer*/\n",
                "/*@Pinfit usercode+ module.source.footer*/\nstatic void helper(void)\n{\n}\n"));
        return source;
    }

    @Test
    void fixPrototypesAcceptedWithYes() throws Exception {
        Path source = moduleWithUnprototypedHelper();
        CliFixture cli = new CliFixture(temporaryDirectory, "yes\n");
        assertEquals(0, cli.run("fix-prototypes"));
        assertTrue(Files.readString(source).contains("static void helper(void);"));
    }

    @Test
    void fixPrototypesDeclinedByEndOfInput() throws Exception {
        Path source = moduleWithUnprototypedHelper();
        String before = Files.readString(source);
        CliFixture cli = new CliFixture(temporaryDirectory, "");
        assertEquals(0, cli.run("fix-prototypes"));
        assertEquals(before, Files.readString(source));
        assertTrue(cli.output().contains("0 prototype(s) added in 0 file(s)"));
    }

    @Test
    void fixPrototypesWithUnreadableInput() throws Exception {
        moduleWithUnprototypedHelper();
        CliFixture cli = new CliFixture(temporaryDirectory, CliFixture.failingInput());
        assertEquals(1, cli.run("fix-prototypes"));
        assertTrue(cli.errors().contains("Cannot read confirmation: stdin closed"), cli.errors());
    }

    @Test
    void fixPrototypesReportsAnUnreadableSource() throws Exception {
        Path source = moduleWithUnprototypedHelper();
        byte[] content = Files.readAllBytes(source);
        byte[] broken = new byte[content.length + 2];
        System.arraycopy(content, 0, broken, 0, content.length);
        broken[content.length] = (byte) 0xC3;
        broken[content.length + 1] = (byte) 0x28;
        Files.write(source, broken);
        CliFixture cli = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(1, cli.run("fix-prototypes"));
        assertTrue(cli.errors().contains("Cannot read " + source.toRealPath()), cli.errors());
    }

    // ---- --also-nested directory count ----

    @Test
    void alsoNestedCountReportsProgressEvery200Directories() throws Exception {
        for (int index = 0; index < 200; index++) {
            Files.createDirectories(temporaryDirectory.resolve("d" + index));
        }
        CliFixture cli = new CliFixture(temporaryDirectory, (InputStream) new java.io.ByteArrayInputStream("n\n".getBytes()));
        assertEquals(1, cli.run("generate", "--also-nested"));
        assertTrue(cli.output().contains("Counting... 1 directory found so far"), cli.output());
        assertTrue(cli.output().contains("Counting... 200 directories found so far"), cli.output());
        assertFalse(cli.output().contains("Generated files:"));
    }
}
