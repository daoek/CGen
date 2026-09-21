package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A non-void function with no invalidReturn/uninitializedReturn anywhere falls back to a zero
 * initializer - for an enum whose 0 member means success, a failed guard then reports success.
 * generate must warn about this by default, and fail under strict mode. See
 * CGenerator.reportReturnDefaultFallbacks.
 */
class ReturnDefaultFallbackTest {
    @TempDir
    Path temporaryDirectory;

    private static final String INTERFACE_WITH_FALLBACK = """
            kind: interface
            name: sensor
            header: sensor_I.h
            includes: []
            enums:
              - name: sensor_status_t
                values:
                  - { name: SENSOR_OK, value: 0 }
                  - { name: SENSOR_FAIL, value: 1 }
            structs: []
            functions:
              - name: read
                return: sensor_status_t
                parameters: []
            """;

    @Test
    void warnsByDefaultButStillGenerates() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("sensor.interface.yaml"), INTERFACE_WITH_FALLBACK);

        assertEquals(0, cli.run("generate"), cli.errors());
        assertTrue(cli.output().contains("Warning"), cli.output());
        assertTrue(cli.output().contains("sensor.interface.yaml"), cli.output());
        assertTrue(cli.output().contains("interface 'sensor'"), cli.output());
        assertTrue(cli.output().contains("function 'read'"), cli.output());
        assertTrue(cli.output().contains("invalidReturn"), cli.output());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("sensor_I.h")));
    }

    @Test
    void failsUnderStrictFlag() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("sensor.interface.yaml"), INTERFACE_WITH_FALLBACK);

        assertEquals(1, cli.run("generate", "--strict"));
        assertTrue(cli.errors().contains("invalidReturn"), cli.errors());
        assertFalse(Files.exists(temporaryDirectory.resolve("sensor_I.h")), "strict failure must write nothing");
    }

    @Test
    void failsUnderStrictProjectSetting() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path projectYaml = temporaryDirectory.resolve("cgen.yaml");
        Files.writeString(projectYaml, Files.readString(projectYaml) + "\nstrict: true\n");
        Files.writeString(temporaryDirectory.resolve("sensor.interface.yaml"), INTERFACE_WITH_FALLBACK);

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("invalidReturn"), cli.errors());
    }

    @Test
    void noWarningWhenARealSentinelIsNamed() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("sensor.interface.yaml"), INTERFACE_WITH_FALLBACK.replace(
                "functions:\n  - name: read\n    return: sensor_status_t\n    parameters: []",
                "functions:\n  - name: read\n    return: sensor_status_t\n    parameters: []\n    "
                        + "invalidReturn: SENSOR_FAIL"));

        assertEquals(0, cli.run("generate"), cli.errors());
        assertFalse(cli.output().contains("Warning"), cli.output());
    }
}
