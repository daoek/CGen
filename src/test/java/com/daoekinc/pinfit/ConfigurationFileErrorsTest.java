package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unreadable or malformed YAML files and custom-documentation files, as reported by generate. */
class ConfigurationFileErrorsTest {
    @TempDir
    Path temporaryDirectory;

    private CliFixture initialized() {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        return cli;
    }

    private void assertGenerateFails(CliFixture cli, String expected) {
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains(expected), cli.errors());
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
            "- a list|must contain a YAML mapping at its root",
            "1: numeric key|contains a non-text key",
            "kind: [unclosed|Cannot read YAML",
    })
    void rejectsMalformedSpecFile(String content, String expected) throws Exception {
        CliFixture cli = initialized();
        Files.writeString(temporaryDirectory.resolve("bad.interface.yaml"), content);
        assertGenerateFails(cli, expected);
    }

    @Test
    void rejectsSpecFileThatIsNotUtf8() throws Exception {
        CliFixture cli = initialized();
        Files.write(temporaryDirectory.resolve("bad.interface.yaml"), new byte[] {'k', ':', ' ', (byte) 0xC3, (byte) 0x28});
        assertGenerateFails(cli, "Cannot read YAML");
    }

    private CliFixture customDocumentation(String documentationYaml) throws Exception {
        CliFixture cli = initialized();
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        Files.writeString(projectFile, Files.readString(projectFile)
                .replace("style: doxygen", "style: custom")
                .replace("# file: documentation.yaml", "file: documentation.yaml"));
        if (documentationYaml != null) {
            Files.writeString(temporaryDirectory.resolve("documentation.yaml"), documentationYaml);
        }
        assertEquals(0, cli.run("create", "interface", "clock"));
        return cli;
    }

    @Test
    void customDocumentationFileMustExist() throws Exception {
        assertGenerateFails(customDocumentation(null), "Custom documentation file not found:");
    }

    @Test
    void customDocumentationRejectsUnknownKeys() throws Exception {
        assertGenerateFails(customDocumentation("chapter: x\n"), "Custom documentation contains unknown key 'chapter'");
    }

    @Test
    void customDocumentationTemplatesMustBeText() throws Exception {
        assertGenerateFails(customDocumentation("file: [x]\n"), "Custom documentation template 'file' must be text");
    }
}
