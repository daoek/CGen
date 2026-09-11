package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesUserCodeAndDetachesCleanly() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertFalse(Files.exists(temporaryDirectory.resolve("interfaces")));
        assertFalse(Files.exists(temporaryDirectory.resolve("modules")));
        assertEquals(0, cli.run("create", "interface", "sensor", "contracts"));
        assertEquals(0, cli.run("create", "module", "demo_sensor", "drivers/demo", "--implements", "sensor"));
        assertEquals(0, cli.run("generate", "contracts"));
        assertFalse(Files.exists(temporaryDirectory.resolve("drivers/demo/demo_sensor.c")));
        assertEquals(0, cli.run("generate", "drivers"));

        Path moduleSource = temporaryDirectory.resolve("drivers/demo/demo_sensor.c");
        String generated = Files.readString(moduleSource);
        assertTrue(generated.contains("/*@CGen usercode+ function.sensor.init.body*/"));
        assertFalse(generated.contains("kind="));
        String customBody = "    module->reserved = 7U;\n    cgen_result = 0;";
        assertFalse(generated.contains("/* Add custom"));
        assertFalse(generated.contains("/* Add implementation."));
        generated = generated.replace(
                "/*@CGen usercode+ function.sensor.init.body*/\n/*@CGen usercode-*/",
                "/*@CGen usercode+ function.sensor.init.body*/\n" + customBody + "\n/*@CGen usercode-*/");
        Files.writeString(moduleSource, generated);

        assertEquals(0, cli.run("gen"));
        assertTrue(Files.readString(moduleSource).contains(customBody));

        Path interfaceYaml = temporaryDirectory.resolve("contracts/sensor.interface.yaml");
        String yaml = Files.readString(interfaceYaml);
        Files.writeString(interfaceYaml, yaml.substring(0, yaml.indexOf("functions:")) + "functions: []\n");
        assertEquals(0, cli.run("generate"));
        String withOrphan = Files.readString(moduleSource);
        assertTrue(withOrphan.contains(customBody));
        assertTrue(withOrphan.contains("@CGen(orphaned-user-region:"));

        CliFixture detachCli = new CliFixture(temporaryDirectory, temporaryDirectory.getFileName() + "\n");
        assertEquals(0, detachCli.run("detach"));
        String cleaned = Files.readString(moduleSource);
        assertFalse(cleaned.contains("/*@CGen"));
        assertTrue(cleaned.contains(customBody));
        assertFalse(Files.exists(temporaryDirectory.resolve("cgen.yaml")));
        assertFalse(Files.exists(interfaceYaml));
        assertFalse(Files.exists(temporaryDirectory.resolve("drivers/demo/demo_sensor.module.yaml")));
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("No cgen.yaml found"));
    }

    @Test
    void generatesTypesVisibilityDocumentationAndMisraFriendlyFlow() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path project = temporaryDirectory.resolve("cgen.yaml");
        Files.writeString(project, Files.readString(project).replace(
                "style: doxygen # doxygen, none, or custom",
                "style: custom\n  file: documentation.yaml"));
        Files.writeString(temporaryDirectory.resolve("documentation.yaml"), """
                file: |
                  /* FILE ${file}: ${brief} */
                function: |
                  /* FUNCTION ${name}: ${brief}; ${params} */
                type: "/* TYPE ${name}: ${brief} */"
                variable: "/* VARIABLE ${name}: ${brief} */"
                """);

        Path interfaceDirectory = Files.createDirectories(temporaryDirectory.resolve("interfaces/bus"));
        Files.writeString(interfaceDirectory.resolve("bus.interface.yaml"), """
                kind: interface
                name: bus
                description: Transfer bytes
                invalidReturn: BUS_INVALID
                uninitializedReturn: BUS_NOT_READY
                includes: [<stdbool.h>, <stdint.h>]
                enums:
                  - name: bus_status_t
                    description: Bus result
                    values:
                      - { name: BUS_OK, value: 0 }
                      - { name: BUS_INVALID, value: 1 }
                      - { name: BUS_NOT_READY, value: 2 }
                structs:
                  - name: bus_options_t
                    description: Bus options
                    fields:
                      - { type: uint32_t, name: speed, description: Bus speed }
                functions:
                  - name: write
                    return: bus_status_t
                    description: Write bytes
                    parameters:
                      - { type: const uint8_t *, name: data, description: Bytes to write }
                      - { type: uint32_t, name: length, description: Byte count }
                """);
        Path moduleDirectory = Files.createDirectories(temporaryDirectory.resolve("modules/demo_bus"));
        Files.writeString(moduleDirectory.resolve("demo_bus.module.yaml"), """
                kind: module
                name: demo_bus
                description: Demo bus
                implements: [bus]
                context:
                  - { type: uint32_t, name: writes, description: Write count }
                variables:
                  - { type: uint32_t, name: public_count, visibility: public, initial: 1U }
                  - { type: uint32_t, name: private_count, visibility: private, initial: 0U }
                """);

        assertEquals(0, cli.run("generate"));
        String interfaceHeader = Files.readString(interfaceDirectory.resolve("bus_I.h"));
        String moduleHeader = Files.readString(moduleDirectory.resolve("demo_bus.h"));
        String moduleSource = Files.readString(moduleDirectory.resolve("demo_bus.c"));
        assertTrue(interfaceHeader.contains("bus_status_t cgen_result = BUS_INVALID;"));
        assertTrue(interfaceHeader.contains("if (interface != NULL)"));
        assertTrue(interfaceHeader.contains("cgen_result = BUS_NOT_READY;"));
        assertEquals(1, occurrences(interfaceHeader, "return cgen_result;"));
        assertTrue(interfaceHeader.contains("/* FUNCTION bus_write: Write bytes; data: Bytes to write, length: Byte count */"));
        assertTrue(moduleHeader.contains("extern uint32_t public_count;"));
        assertTrue(moduleHeader.contains("uint32_t writes;"));
        assertTrue(moduleSource.contains("uint32_t public_count = 1U;"));
        assertTrue(moduleSource.contains("static uint32_t private_count = 0U;"));
        assertTrue(moduleSource.contains("(void)data;"));
        assertTrue(moduleSource.contains("(void)length;"));
        assertTrue(moduleSource.contains("bus_status_t cgen_result = BUS_INVALID;"));
        assertFalse(moduleSource.contains("return;"));
    }

    private static int occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
