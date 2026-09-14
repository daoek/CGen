package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlErgonomicsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compactTypeNameShorthandWorksForParametersFieldsAndVariables() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("bus.interface.yaml"), """
                kind: interface
                name: bus
                invalidReturn: -1
                includes: [<stdint.h>]
                structs:
                  - name: bus_options_t
                    fields:
                      - uint32_t speed
                functions:
                  - name: write
                    return: int
                    parameters:
                      - uint8_t *data
                      - uint32_t length
                """);
        Files.writeString(temporaryDirectory.resolve("demo_bus.module.yaml"), """
                kind: module
                name: demo_bus
                implements: [bus]
                includes: []
                context:
                  - uint32_t hardware_id
                variables:
                  - uint32_t transfer_count public
                  - bool busy
                """);

        assertEquals(0, cli.run("generate"));

        String interfaceHeader = Files.readString(temporaryDirectory.resolve("bus_I.h"));
        assertTrue(interfaceHeader.contains("uint32_t speed;"));
        assertTrue(interfaceHeader.contains("uint8_t *data"));
        assertTrue(interfaceHeader.contains("uint32_t length"));

        String moduleHeader = Files.readString(temporaryDirectory.resolve("demo_bus.h"));
        String moduleSource = Files.readString(temporaryDirectory.resolve("demo_bus.c"));
        assertTrue(moduleHeader.contains("uint32_t hardware_id;"));
        assertTrue(moduleHeader.contains("extern uint32_t transfer_count;"));
        assertTrue(moduleSource.contains("uint32_t transfer_count;"));
        assertTrue(moduleSource.contains("static bool busy;"));
    }

    @Test
    void distinctiveUsercodeMarkersReplaceOldFormat() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "clock"));
        assertEquals(0, cli.run("generate"));
        String header = Files.readString(temporaryDirectory.resolve("clock_I.h"));
        assertTrue(header.contains("/*@CGen usercode+ interface.preamble*/"));
        assertTrue(header.contains("/*@CGen usercode-*/"));
        assertTrue(!header.contains("@CGen(+"));
        assertTrue(!header.contains("@CGen(-"));
    }

    @Test
    void publicVariablesAsAccessorsGeneratesGetterSetterWithUserRegion() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path project = temporaryDirectory.resolve("cgen.yaml");
        Files.writeString(project, Files.readString(project).replace(
                "format:\n  indent: 4\n  lineEnding: lf",
                "format:\n  indent: 4\n  lineEnding: lf\n  publicVariables: accessors"));

        Files.writeString(temporaryDirectory.resolve("logger.module.yaml"), """
                kind: module
                name: logger
                implements: []
                includes: []
                context: []
                variables:
                  - uint32_t log_count public
                """);

        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("logger.h"));
        String source = Files.readString(temporaryDirectory.resolve("logger.c"));
        assertTrue(header.contains("uint32_t logger_get_log_count(void);"));
        assertTrue(header.contains("void logger_set_log_count(uint32_t value);"));
        assertTrue(!header.contains("extern"));
        assertTrue(source.contains("static uint32_t log_count;"));
        assertTrue(source.contains("uint32_t logger_get_log_count(void)"));
        assertTrue(source.contains("return log_count;"));
        assertTrue(source.contains("void logger_set_log_count(uint32_t value)"));
        assertTrue(source.contains("log_count = value;"));
        assertTrue(source.contains("/*@CGen usercode+ variable.log_count.set*/"));

        Path sourcePath = temporaryDirectory.resolve("logger.c");
        String customSet = "    if (value <= 1000U)\n    {\n        log_count = value;\n    }";
        String updated = source.replace(
                "    /*@CGen usercode+ variable.log_count.set*/\n    log_count = value;\n    /*@CGen usercode-*/",
                "    /*@CGen usercode+ variable.log_count.set*/\n" + customSet + "\n    /*@CGen usercode-*/");
        Files.writeString(sourcePath, updated);

        assertEquals(0, cli.run("gen"));
        assertTrue(Files.readString(sourcePath).contains(customSet));
    }

    @Test
    void functionNamingCamelCaseAppliesToFunctionsButNotTypes() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path project = temporaryDirectory.resolve("cgen.yaml");
        Files.writeString(project, Files.readString(project).replace(
                "format:\n  indent: 4\n  lineEnding: lf",
                "format:\n  indent: 4\n  lineEnding: lf\n  functionNaming: camelCase"));

        Files.writeString(temporaryDirectory.resolve("my_sensor.interface.yaml"), """
                kind: interface
                name: my_sensor
                includes: []
                functions:
                  - name: init
                    return: int
                    invalidReturn: -1
                    description: Initialize the interface
                    parameters: []
                """);
        Files.writeString(temporaryDirectory.resolve("motor_driver.module.yaml"), """
                kind: module
                name: motor_driver
                implements: [my_sensor]
                includes: []
                context: []
                variables: []
                """);
        assertEquals(0, cli.run("generate"));

        String interfaceHeader = Files.readString(temporaryDirectory.resolve("my_sensor_I.h"));
        assertTrue(interfaceHeader.contains("static inline int mySensorInit(const my_sensor_interface_t * const interface)"));

        String moduleHeader = Files.readString(temporaryDirectory.resolve("motor_driver.h"));
        String moduleSource = Files.readString(temporaryDirectory.resolve("motor_driver.c"));
        assertTrue(moduleHeader.contains("typedef void motor_driver_context_t;"));
        assertTrue(moduleHeader.contains("void motorDriverBindMySensor(my_sensor_interface_t *interface, motor_driver_context_t *context);"));
        assertTrue(moduleSource.contains("static int motorDriverMySensorInit(void *context)"));
        assertTrue(moduleSource.contains("void motorDriverBindMySensor(my_sensor_interface_t *interface, motor_driver_context_t *context)"));
        assertTrue(moduleSource.contains("interface->init = motorDriverMySensorInit;"));
    }
}
