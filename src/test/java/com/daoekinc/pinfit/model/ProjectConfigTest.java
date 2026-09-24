package com.daoekinc.pinfit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

class ProjectConfigTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
            "schema: 2|uses unsupported schema 2",
            "documentation: {style: fancy}|documentation.style must be doxygen, none, or custom",
            "documentation: {style: custom}|documentation.file is required when documentation.style is custom",
            "documentation: {file: ../outside.yaml}|documentation.file must stay inside the project",
            "format: {indent: 1}|format.indent must be between 2 and 8",
            "format: {indent: 9}|format.indent must be between 2 and 8",
            "format: {lineEnding: cr}|format.lineEnding must be lf or crlf",
            "format: {publicVariables: global}|format.publicVariables must be extern or accessors",
            "format: {functionNaming: PascalCase}|format.functionNaming must be snake_case or camelCase",
    })
    void rejectsInvalidSettings(String extraYaml, String expectedMessage) {
        PinfitException error = assertThrows(PinfitException.class, () -> load(extraYaml));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    @Test
    void rejectsAbsoluteDocumentationFile() {
        String absolute = temporaryDirectory.resolve("docs.yaml").toAbsolutePath().toString().replace("\\", "/");
        PinfitException error = assertThrows(PinfitException.class, () -> load("documentation: {file: '" + absolute + "'}"));
        assertTrue(error.getMessage().contains("documentation.file must be relative to the project"), error.getMessage());
    }

    @Test
    void rejectsUnparseableDocumentationFile() {
        Map<String, Object> map = yaml("");
        map.put("documentation", Map.of("file", "bad\u0000name"));
        PinfitException error = assertThrows(PinfitException.class,
                () -> ProjectConfig.from(temporaryDirectory.resolve("pinfit.yaml"), map));
        assertEquals("documentation.file is not a valid path", error.getMessage());
    }

    @Test
    void acceptsCrlfAndCustomDocumentation() {
        ProjectConfig project = load("format: {lineEnding: CRLF}\ndocumentation: {style: custom, file: docs.yaml}");
        assertEquals("\r\n", project.lineEnding());
        assertEquals(project.root().resolve("docs.yaml"), project.documentation().customFile());
        assertEquals("none", load("documentation: {style: None}").documentation().style());
    }

    @Test
    void failsWhenTheProjectDirectoryDoesNotExist() {
        Path missing = temporaryDirectory.resolve("missing").resolve("pinfit.yaml");
        PinfitException error = assertThrows(PinfitException.class, () -> ProjectConfig.from(missing, yaml("")));
        assertTrue(error.getMessage().startsWith("Cannot resolve project root for "), error.getMessage());
    }

    private ProjectConfig load(String extraYaml) {
        return ProjectConfig.from(temporaryDirectory.resolve("pinfit.yaml"), yaml(extraYaml));
    }

    private static Map<String, Object> yaml(String extraYaml) {
        Map<String, Object> map = new Yaml().load("name: demo\nversion: 1.0.0\n" + extraYaml);
        return map;
    }
}
