package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwitchTagTest {
    @TempDir
    Path temporaryDirectory;

    private void writeFlashModule(Path directory) throws Exception {
        Files.writeString(directory.resolve("flash.module.yaml"), """
                kind: module
                name: flash
                implements: []
                includes: []
                context: []
                variables: []
                enums:
                  - name: flash_opcodes_t
                    values:
                      - { name: FLASH_OP_READ }
                      - { name: FLASH_OP_WRITE }
                functions:
                  - name: dispatch_opcode
                    return: void
                    parameters:
                      - flash_opcodes_t opcode
                    visibility: public
                """);
    }

    @Test
    void switchTagFillsInCasesFromLocallyDeclaredEnum() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        writeFlashModule(temporaryDirectory);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("flash.c");
        String generated = Files.readString(source);
        String withTag = generated.replace(
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n"
                        + "    /*@PinfitSwitch flash_opcodes_t*/\n"
                        + "    switch (opcode)\n"
                        + "    {\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/");
        Files.writeString(source, withTag);

        assertEquals(0, cli.run("generate"));
        String result = Files.readString(source);
        assertTrue(result.contains("case FLASH_OP_READ:"));
        assertTrue(result.contains("case FLASH_OP_WRITE:"));
        assertTrue(result.contains("/*@Pinfit usercode+ switchcase.flash_opcodes_t.FLASH_OP_READ*/"));
        assertTrue(result.contains("/*@Pinfit usercode+ switchcase.flash_opcodes_t.default*/"));
    }

    @Test
    void switchTagMigratesExistingHandWrittenCaseBodies() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        writeFlashModule(temporaryDirectory);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("flash.c");
        String generated = Files.readString(source);
        String withHandWrittenSwitch = generated.replace(
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n"
                        + "    /*@PinfitSwitch flash_opcodes_t*/\n"
                        + "    switch (opcode)\n"
                        + "    {\n"
                        + "        case FLASH_OP_READ:\n"
                        + "            do_read();\n"
                        + "            break;\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/");
        Files.writeString(source, withHandWrittenSwitch);

        assertEquals(0, cli.run("generate"));
        String result = Files.readString(source);
        assertTrue(result.contains("do_read();"), "existing case code must be migrated, not discarded");
        assertTrue(result.contains("case FLASH_OP_WRITE:"), "missing case must be added");
    }

    @Test
    void switchTagIsStableAcrossRepeatedRegeneration() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        writeFlashModule(temporaryDirectory);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("flash.c");
        String generated = Files.readString(source);
        Files.writeString(source, generated.replace(
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n"
                        + "    /*@PinfitSwitch flash_opcodes_t*/\n"
                        + "    switch (opcode)\n"
                        + "    {\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/"));
        assertEquals(0, cli.run("generate"));
        String firstPass = Files.readString(source);

        assertEquals(0, cli.run("generate"));
        String secondPass = Files.readString(source);
        assertEquals(firstPass, secondPass, "a second regeneration with no YAML changes must produce identical output");
    }

    @Test
    void switchTagRejectsUnknownLegacyCaseName() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        writeFlashModule(temporaryDirectory);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("flash.c");
        String generated = Files.readString(source);
        Files.writeString(source, generated.replace(
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n"
                        + "    /*@PinfitSwitch flash_opcodes_t*/\n"
                        + "    switch (opcode)\n"
                        + "    {\n"
                        + "        case FLASH_OP_TYPO:\n"
                        + "            do_typo();\n"
                        + "            break;\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/"));

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("FLASH_OP_TYPO"));
    }

    @Test
    void switchTagResolvesExternalEnumWithConfirmation() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("external.h"), """
                #ifndef EXTERNAL_H_
                #define EXTERNAL_H_
                typedef enum
                {
                    MOTOR_FAULT_OVERCURRENT,
                    MOTOR_FAULT_STALL
                } motor_fault_t;
                #endif
                """);
        Files.writeString(temporaryDirectory.resolve("motor.module.yaml"), """
                kind: module
                name: motor
                implements: []
                includes: ['"external.h"']
                context: []
                variables: []
                functions:
                  - name: handle_fault
                    return: void
                    parameters:
                      - motor_fault_t fault
                    visibility: public
                """);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("motor.c");
        String generated = Files.readString(source);
        Files.writeString(source, generated.replace(
                "/*@Pinfit usercode+ function.handle_fault.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.handle_fault.body*/\n"
                        + "    /*@PinfitSwitch motor_fault_t*/\n"
                        + "    switch (fault)\n"
                        + "    {\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/"));

        CliFixture declined = new CliFixture(temporaryDirectory, "n\n");
        assertEquals(1, declined.run("generate"));
        assertEquals(generated.replace(
                "/*@Pinfit usercode+ function.handle_fault.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.handle_fault.body*/\n"
                        + "    /*@PinfitSwitch motor_fault_t*/\n"
                        + "    switch (fault)\n"
                        + "    {\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/"),
                Files.readString(source), "declining must not modify the file");

        Path moduleYaml = temporaryDirectory.resolve("motor.module.yaml");
        String moduleYamlBeforeConfirm = Files.readString(moduleYaml);

        CliFixture confirmed = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, confirmed.run("generate"));
        String result = Files.readString(source);
        assertTrue(result.contains("case MOTOR_FAULT_OVERCURRENT:"));
        assertTrue(result.contains("case MOTOR_FAULT_STALL:"));
        assertTrue(confirmed.output().contains("Found a matching 'typedef enum' in"));

        String moduleYamlAfterConfirm = Files.readString(moduleYaml);
        assertTrue(!moduleYamlAfterConfirm.equals(moduleYamlBeforeConfirm), "module.yaml must change once the enum is confirmed");
        assertTrue(!moduleYamlAfterConfirm.contains("\nenums:"), "a linked enum must never be added under enums:"
                + " - Pinfit would then also emit its own typedef, redeclaring a type the .h file already defines");
        assertTrue(moduleYamlAfterConfirm.contains("externalEnums:"), "confirmed enum must be linked, not copied");
        assertTrue(moduleYamlAfterConfirm.contains("{ name: motor_fault_t, file: external.h }"));

        String header = Files.readString(temporaryDirectory.resolve("motor.h"));
        assertTrue(!header.contains("typedef enum"), "Pinfit must not redeclare an enum it only linked to");

        // A further generate must resolve the linked enum from its file without asking again.
        CliFixture again = new CliFixture(temporaryDirectory);
        assertEquals(0, again.run("generate"));
        assertTrue(again.output().isEmpty() || !again.output().contains("Use this enum?"));
        assertEquals(moduleYamlAfterConfirm, Files.readString(moduleYaml), "no further YAML change once linked");
    }

    /**
     * The legacy {@code @CGenSwitch} spelling (from before the CGen-to-Pinfit rebrand) must keep
     * expanding forever, not just until the next regenerate - unlike a generated marker, this tag
     * lives in the user's own hand-written code and Pinfit never rewrites that line.
     */
    @Test
    void legacyCGenSwitchSpellingStillExpands() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        writeFlashModule(temporaryDirectory);
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("flash.c");
        String generated = Files.readString(source);
        String withLegacyTag = generated.replace(
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.dispatch_opcode.body*/\n"
                        + "    /*@CGenSwitch flash_opcodes_t*/\n"
                        + "    switch (opcode)\n"
                        + "    {\n"
                        + "    }\n"
                        + "    /*@Pinfit usercode-*/");
        Files.writeString(source, withLegacyTag);

        assertEquals(0, cli.run("generate"));
        String result = Files.readString(source);
        assertTrue(result.contains("case FLASH_OP_READ:"));
        assertTrue(result.contains("case FLASH_OP_WRITE:"));
        // The tag itself is user-authored and never rewritten - it must survive verbatim.
        assertTrue(result.contains("/*@CGenSwitch flash_opcodes_t*/"), "legacy tag spelling must be left as-is");
    }
}
