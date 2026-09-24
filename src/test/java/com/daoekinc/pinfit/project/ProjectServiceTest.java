package com.daoekinc.pinfit.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import com.daoekinc.pinfit.config.YamlFiles;
import com.daoekinc.pinfit.model.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Project file handling, directory safety checks and the external-enum YAML insertion. */
class ProjectServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final ProjectService projects = new ProjectService(new YamlFiles());
    private Path root;
    private ProjectConfig project;

    @BeforeEach
    void createProject() throws Exception {
        root = Files.createDirectories(temporaryDirectory.resolve("project"));
        projects.init(root, false);
        project = projects.findAndLoad(root);
    }

    private static void assertFails(String expected, Executable executable) {
        PinfitException error = assertThrows(PinfitException.class, executable);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    // ---- init / migration ----

    @Test
    void initIntoAFileFails() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "");
        assertFails("Cannot initialize project at", () -> projects.init(file.resolve("sub"), false));
    }

    @Test
    void migrationKeepsContentWithoutTheOldCommentAndRefusesToOverwrite() throws Exception {
        Path legacy = Files.writeString(temporaryDirectory.resolve("cgen.yaml"), "name: x\nversion: 1\n");
        Path migrated = projects.migrateLegacyProjectFile(legacy);
        assertEquals("name: x\nversion: 1\n", Files.readString(migrated));

        Path blocked = Files.writeString(temporaryDirectory.resolve("cgen.yaml"), "name: y\n");
        assertFails("Cannot migrate", () -> projects.migrateLegacyProjectFile(blocked));
    }

    // ---- create ----

    @Test
    void createRejectsBadNamesAndExistingSpecs() {
        assertFails("interface name must be a valid C identifier, got 'bad-name'", () -> projects.createInterface(project, "bad-name", root));
        projects.createStateMachine(project, "door", root);
        assertTrue(Files.exists(root.resolve("door.state-machine.yaml")));
        assertFails("already exists; create will not overwrite it", () -> projects.createStateMachine(project, "door", root));
    }

    // ---- directories ----

    @Test
    void safeDirectoryRejectsOutsideAndUncreatablePaths() throws Exception {
        assertFails("Path resolves outside project", () -> projects.safeDirectory(project, temporaryDirectory.resolve("outside")));
        Path file = Files.writeString(root.resolve("file"), "");
        assertFails("Cannot create directory", () -> projects.safeDirectory(project, file.resolve("sub")));
    }

    @Test
    void existingDirectoryMustBeAnExistingDirectoryInsideTheProject() throws Exception {
        Path file = Files.writeString(root.resolve("file"), "");
        assertFails("Directory must exist inside the project", () -> projects.existingDirectory(project, file));
        assertFails("Directory must exist inside the project", () -> projects.existingDirectory(project, temporaryDirectory));
        assertFails("Directory must exist inside the project", () -> projects.existingDirectory(project, root.resolve("missing")));
    }

    // ---- appendExternalEnumLink ----

    private Path spec(String content) throws Exception {
        return Files.writeString(root.resolve("m.module.yaml"), content);
    }

    @Test
    void linksIntoAnEmptyExternalEnumsList() throws Exception {
        Path spec = spec("kind: module\nname: m\nexternalEnums: []\nincludes: []\n");
        projects.appendExternalEnumLink(spec, "e_t", "e.h");
        assertEquals("kind: module\nname: m\nexternalEnums:\n  - { name: e_t, file: e.h }\nincludes: []\n", Files.readString(spec));
    }

    @Test
    void appendsToAnExistingExternalEnumsBlockKeepingCrlfAndNoTrailingNewline() throws Exception {
        Path spec = spec("kind: module\r\nname: m\r\nexternalEnums:\r\n  - { name: a_t, file: a.h }\r\n\r\nincludes: []");
        projects.appendExternalEnumLink(spec, "e_t", "e.h");
        assertEquals("kind: module\r\nname: m\r\nexternalEnums:\r\n  - { name: a_t, file: a.h }\r\n  - { name: e_t, file: e.h }\r\n\r\n"
                + "includes: []", Files.readString(spec));
    }

    @Test
    void addsANewBlockAfterABlockStyleIncludesList() throws Exception {
        Path spec = spec("kind: module\nname: m\nincludes:\n  - <stdint.h>\n  - <stdbool.h>\n");
        projects.appendExternalEnumLink(spec, "e_t", "e.h");
        assertEquals("kind: module\nname: m\nincludes:\n  - <stdint.h>\n  - <stdbool.h>\n\nexternalEnums:\n  - { name: e_t, file: e.h }\n",
                Files.readString(spec));
    }

    @Test
    void addsANewBlockBetweenAnIncludesListAndTheNextKey() throws Exception {
        Path spec = spec("kind: module\nincludes:\n  - <stdint.h>\nname: m\n");
        projects.appendExternalEnumLink(spec, "e_t", "e.h");
        assertEquals("kind: module\nincludes:\n  - <stdint.h>\n\nexternalEnums:\n  - { name: e_t, file: e.h }\nname: m\n",
                Files.readString(spec));
    }

    @Test
    void plainFindAndLoadNeverMigratesALegacyProjectFile() throws Exception {
        Path legacyRoot = Files.createDirectories(temporaryDirectory.resolve("legacy"));
        Files.writeString(legacyRoot.resolve("cgen.yaml"), "name: x\nversion: 1\n");
        assertThrows(PinfitException.class, () -> projects.findAndLoad(legacyRoot));
        assertTrue(Files.exists(legacyRoot.resolve("cgen.yaml")));
    }

    @Test
    void refusesWithoutAnIncludesKeyToAnchorTo() throws Exception {
        Path spec = spec("kind: module\nname: m\n");
        assertFails("cannot find 'includes:'", () -> projects.appendExternalEnumLink(spec, "e_t", "e.h"));
    }

    @Test
    void rollsBackWhenTheResultIsNotValidYaml() throws Exception {
        String original = "kind: module\nincludes:\n\t- <a.h>\n";
        Path spec = spec(original);
        assertFails("could not link enum 'e_t' automatically (change rolled back)", () -> projects.appendExternalEnumLink(spec, "e_t", "e.h"));
        assertEquals(original, Files.readString(spec));
    }

    @Test
    void reportsUnreadableAndUnwritableSpecFiles() throws Exception {
        assertFails("Cannot read", () -> projects.appendExternalEnumLink(root.resolve("missing.module.yaml"), "e_t", "e.h"));

        Path spec = spec("kind: module\nincludes: []\n");
        assertTrue(spec.toFile().setWritable(false));
        try {
            assertFails("Cannot write", () -> projects.appendExternalEnumLink(spec, "e_t", "e.h"));
        } finally {
            spec.toFile().setWritable(true);
        }
    }
}
