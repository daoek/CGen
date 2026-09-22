package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetachTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresExactProjectNameAndKeepsUnrelatedYaml() throws Exception {
        CliFixture setup = new CliFixture(temporaryDirectory);
        assertEquals(0, setup.run("init"));
        Path projectYaml = temporaryDirectory.resolve("pinfit.yaml");
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
        assertTrue(Files.exists(projectYaml));
        assertTrue(Files.readString(temporaryDirectory.resolve("sensor_I.h")).contains("/*@Pinfit"));
        assertTrue(Files.exists(unrelated));

        CliFixture confirmed = new CliFixture(temporaryDirectory, temporaryDirectory.getFileName() + "\n");
        assertEquals(0, confirmed.run("detach"));
        assertFalse(Files.exists(projectYaml));
        assertFalse(Files.exists(temporaryDirectory.resolve("sensor.interface.yaml")));
        assertFalse(Files.exists(documentationYaml));
        assertFalse(Files.readString(temporaryDirectory.resolve("sensor_I.h")).contains("/*@Pinfit"));
        assertTrue(Files.exists(unrelated));
    }
}
