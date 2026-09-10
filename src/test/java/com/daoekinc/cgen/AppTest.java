package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.cgen.cli.CGenCli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completeWorkflowPreservesUserCodeAndCleansTags() throws Exception {
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
        assertTrue(generated.contains("/*@CGen(+function.sensor.init.body)*/"));
        assertFalse(generated.contains("kind="));
        String customBody = "    module->reserved = 7U;\n    return 0;";
        assertFalse(generated.contains("/* Add custom"));
        assertFalse(generated.contains("/* Add implementation."));
        generated = generated.replace(
                "/*@CGen(+function.sensor.init.body)*/\n/*@CGen(-function.sensor.init.body)*/",
                "/*@CGen(+function.sensor.init.body)*/\n" + customBody + "\n/*@CGen(-function.sensor.init.body)*/");
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
    void generatesTypesVisibilityAndCustomDocumentation() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path project = temporaryDirectory.resolve("cgen.yaml");
        String projectYaml = Files.readString(project).replace(
                "style: doxygen # doxygen, none, or custom",
                "style: custom\n  file: documentation.yaml");
        Files.writeString(project, projectYaml);
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
                includes:
                  - <stdbool.h>
                  - <stdint.h>
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
        assertTrue(interfaceHeader.contains("typedef enum"));
        assertTrue(interfaceHeader.contains("bus_status_t (*write)(void *context, const uint8_t *data, uint32_t length)"));
        assertTrue(interfaceHeader.contains("/* FUNCTION bus_write: Write bytes; data: Bytes to write, length: Byte count */"));
        assertTrue(moduleHeader.contains("extern uint32_t public_count;"));
        assertTrue(moduleHeader.contains("uint32_t writes;"));
        assertTrue(moduleSource.contains("uint32_t public_count = 1U;"));
        assertTrue(moduleSource.contains("static uint32_t private_count = 0U;"));
    }

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
    void reportsExpectedParameterYamlSyntax() throws Exception {
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
    void removesUntouchedFunctionRegionsWhenModuleStopsImplementingInterface() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "common_iic"));
        assertEquals(0, cli.run("create", "module", "ra_iic_master", "--implements", "common_iic"));
        assertEquals(0, cli.run("generate"));

        Path moduleYaml = temporaryDirectory.resolve("ra_iic_master.module.yaml");
        String yaml = Files.readString(moduleYaml).replace("implements:\n  - common_iic", "implements: []");
        Files.writeString(moduleYaml, yaml);
        assertEquals(0, cli.run("generate"));

        String source = Files.readString(temporaryDirectory.resolve("ra_iic_master.c"));
        assertFalse(source.contains("common_iic"));
        assertFalse(source.contains("orphaned-user-region"));
        assertFalse(source.contains("return -1;"));
    }

    @Test
    void detachCancelsWithoutExactProjectNameAndKeepsUnrelatedYaml() throws Exception {
        CliFixture setup = new CliFixture(temporaryDirectory);
        assertEquals(0, setup.run("init"));
        Path projectYaml = temporaryDirectory.resolve("cgen.yaml");
        Files.writeString(projectYaml, Files.readString(projectYaml).replace(
                "style: doxygen # doxygen, none, or custom",
                "style: custom\n  file: documentation.yaml"));
        Path documentationYaml = temporaryDirectory.resolve("documentation.yaml");
        Files.writeString(documentationYaml, "{}\n");
        assertEquals(0, setup.run("create", "interface", "sensor"));
        assertEquals(0, setup.run("generate"));
        Path unrelated = temporaryDirectory.resolve("pipeline.yaml");
        Files.writeString(unrelated, "jobs: []\n");

        CliFixture cancelled = new CliFixture(temporaryDirectory, "wrong-name\n");
        assertEquals(1, cancelled.run("detach"));
        assertTrue(cancelled.output().contains("Detach cancelled. No files were changed."));
        assertTrue(Files.exists(temporaryDirectory.resolve("cgen.yaml")));
        assertTrue(Files.readString(temporaryDirectory.resolve("sensor_I.h")).contains("/*@CGen"));
        assertTrue(Files.exists(unrelated));

        CliFixture confirmed = new CliFixture(temporaryDirectory, temporaryDirectory.getFileName() + "\n");
        assertEquals(0, confirmed.run("detach"));
        assertFalse(Files.exists(temporaryDirectory.resolve("cgen.yaml")));
        assertFalse(Files.exists(temporaryDirectory.resolve("sensor.interface.yaml")));
        assertFalse(Files.exists(documentationYaml));
        assertFalse(Files.readString(temporaryDirectory.resolve("sensor_I.h")).contains("/*@CGen"));
        assertTrue(Files.exists(unrelated));
    }

    private static final class CliFixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        private final CGenCli cli;

        private CliFixture(Path directory) {
            this(directory, "");
        }

        private CliFixture(Path directory, String input) {
            cli = new CGenCli(directory, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(output), new PrintStream(errors));
        }

        private int run(String... arguments) {
            return cli.run(arguments);
        }

        private String errors() {
            return errors.toString(StandardCharsets.UTF_8);
        }

        private String output() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
