package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.config.YamlFiles;
import com.daoekinc.pinfit.generate.PinfitGenerator;
import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.project.ProjectService;
import com.daoekinc.pinfit.tag.TagHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-spec validation, @PinfitSwitch enum resolution, rename and detach edge cases of
 * {@link PinfitGenerator}, driven through the CLI where a user would hit them.
 */
class GeneratorScenariosTest {
    @TempDir
    Path temporaryDirectory;

    private CliFixture cli;

    private void init() {
        cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
    }

    private Path write(String file, String content) throws Exception {
        Path path = temporaryDirectory.resolve(file);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private void assertFails(String expected, String... args) {
        CliFixture fresh = new CliFixture(temporaryDirectory, "y\ny\ny\n");
        assertEquals(1, fresh.run(args.length == 0 ? new String[] {"generate"} : args), fresh.output());
        assertTrue(fresh.errors().contains(expected), fresh.errors());
    }

    // ---- cross-spec validation ----

    @Test
    void moduleImplementingUnknownInterface() throws Exception {
        init();
        write("m.module.yaml", "kind: module\nname: m\nimplements: [missing]\n");
        assertFails("implements unknown interface 'missing'");
    }

    @Test
    void duplicateInterfaceNames() throws Exception {
        init();
        write("a/io.interface.yaml", "kind: interface\nname: io\n");
        write("b/io.interface.yaml", "kind: interface\nname: io\n");
        assertFails("Duplicate interface name 'io'");
    }

    @Test
    void twoSpecsGeneratingTheSameFile() throws Exception {
        init();
        write("a.interface.yaml", "kind: interface\nname: a\nheader: same.h\n");
        write("b.interface.yaml", "kind: interface\nname: b\nheader: same.h\n");
        assertFails("Multiple YAML specifications generate");
    }

    @Test
    void adapterReferencesAndMappingsAreChecked() throws Exception {
        init();
        write("x.interface.yaml", "kind: interface\nname: x\nfunctions: [{name: f, parameters: [int a]}]\n");
        write("y.interface.yaml", "kind: interface\nname: y\nfunctions: [{name: g, parameters: [long a]}]\n");
        Path adapter = write("ad.adapter.yaml", "kind: adapter\nname: ad\nfrom: x\nto: nope\n");
        assertFails("references unknown to interface 'nope'");

        Files.writeString(adapter, "kind: adapter\nname: ad\nfrom: x\nto: y\nmappings: [{from: nope, to: g}]\n");
        assertFails("maps unknown function 'nope' on interface 'x'");

        Files.writeString(adapter, "kind: adapter\nname: ad\nfrom: x\nto: y\nmappings: [{from: f, to: nope}]\n");
        assertFails("maps unknown function 'nope' on interface 'y'");

        Files.writeString(adapter, "kind: adapter\nname: ad\nfrom: x\nto: y\nmappings: [{from: f, to: g}]\n");
        assertFails("has mismatched parameter types");
    }

    @Test
    void specsUnderExcludedDirectoriesAndSpecNamedDirectoriesAreIgnored() throws Exception {
        init();
        write(".git/hidden.interface.yaml", "kind: interface\nname: hidden\n");
        write("target/built.interface.yaml", "kind: interface\nname: built\n");
        Files.createDirectories(temporaryDirectory.resolve("folder.interface.yaml"));
        assertEquals(0, cli.run("generate"), cli.errors());
        assertFalse(Files.exists(temporaryDirectory.resolve(".git/hidden_I.h")));
        assertFalse(Files.exists(temporaryDirectory.resolve("target/built_I.h")));
    }

    // ---- @PinfitSwitch enum resolution ----

    private Path moduleWithSwitch(String module, String enumType, String functionsYaml, String... functions) throws Exception {
        write(module + ".module.yaml", "kind: module\nname: " + module + "\nincludes: []\nfunctions:\n" + functionsYaml);
        assertEquals(0, new CliFixture(temporaryDirectory).run("generate"));
        Path source = temporaryDirectory.resolve(module + ".c");
        String content = Files.readString(source);
        for (String function : functions) {
            content = content.replace("/*@Pinfit usercode+ function." + function + ".body*/\n    /*@Pinfit usercode-*/",
                    "/*@Pinfit usercode+ function." + function + ".body*/\n    /*@PinfitSwitch " + enumType
                            + "*/\n    switch (v)\n    {\n    }\n    /*@Pinfit usercode-*/");
        }
        Files.writeString(source, content);
        return source;
    }

    @Test
    void switchOnAnEnumDeclaredNowhere() throws Exception {
        init();
        moduleWithSwitch("m", "ghost_t", "  - { name: f, parameters: [int v] }\n", "f");
        assertFails("@PinfitSwitch ghost_t is not declared in any YAML enums: block");
    }

    @Test
    void switchOnConflictingTypedefs() throws Exception {
        init();
        write("a.h", "typedef enum { X, Y } e_t;\n");
        write("b.h", "typedef enum { X, Z } e_t;\n");
        moduleWithSwitch("m", "e_t", "  - { name: f, parameters: [int v] }\n", "f");
        assertFails("matches conflicting 'typedef enum' definitions");
    }

    @Test
    void switchOnATypedefWithoutMembers() throws Exception {
        init();
        write("a.h", "typedef enum { /* none */ } e_t;\n");
        moduleWithSwitch("m", "e_t", "  - { name: f, parameters: [int v] }\n", "f");
        assertFails("Matched 'typedef enum' has no members");
    }

    @Test
    void switchLinksOnceForTwoUsesAndSkipsUnreadableHeaders() throws Exception {
        init();
        Files.write(temporaryDirectory.resolve("binary.h"), new byte[] {(byte) 0xC3, (byte) 0x28});
        write("a.h", "typedef enum {\n    X = 1,\n    Y,\n} e_t;\n");
        Path source = moduleWithSwitch("m", "e_t", "  - { name: f, parameters: [int v] }\n  - { name: g, parameters: [int v] }\n", "f", "g");
        CliFixture confirmed = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, confirmed.run("generate"), confirmed.errors());
        String yaml = Files.readString(temporaryDirectory.resolve("m.module.yaml"));
        assertEquals(1, yaml.split("name: e_t", -1).length - 1, yaml);
        assertEquals(2, Files.readString(source).split("case Y:", -1).length - 1);
    }

    @Test
    void conflictingYamlEnumDeclarations() throws Exception {
        init();
        write("a.interface.yaml", "kind: interface\nname: a\nenums: [{name: e_t, values: [{name: X}]}]\n");
        write("b.interface.yaml", "kind: interface\nname: b\nenums: [{name: e_t, values: [{name: Y}]}]\n");
        moduleWithSwitch("m", "e_t", "  - { name: f, parameters: [int v] }\n", "f");
        assertFails("Enum 'e_t' is declared with different members in more than one YAML file");
    }

    @Test
    void sameYamlEnumDeclaredTwiceIdenticallyIsFine() throws Exception {
        init();
        write("a.interface.yaml", "kind: interface\nname: a\nenums: [{name: e_t, values: [{name: X}]}]\n");
        write("b.interface.yaml", "kind: interface\nname: b\nenums: [{name: e_t, values: [{name: X}]}]\n");
        Path source = moduleWithSwitch("m", "e_t", "  - { name: f, parameters: [int v] }\n", "f");
        assertEquals(0, new CliFixture(temporaryDirectory).run("generate"));
        assertTrue(Files.readString(source).contains("case X:"));
    }

    @Test
    void brokenExternalEnumLinks() throws Exception {
        init();
        Path module = write("m.module.yaml", "kind: module\nname: m\nincludes: []\nexternalEnums: [{name: e_t, file: e.h}]\n"
                + "functions:\n  - { name: f, parameters: [int v] }\n");
        assertEquals(0, new CliFixture(temporaryDirectory).run("generate"));
        Path source = temporaryDirectory.resolve("m.c");
        Files.writeString(source, Files.readString(source).replace("/*@Pinfit usercode+ function.f.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.f.body*/\n    /*@PinfitSwitch e_t*/\n    switch (v)\n    {\n    }\n    /*@Pinfit usercode-*/"));
        assertFails("Linked enum file");

        Files.write(temporaryDirectory.resolve("e.h"), new byte[] {(byte) 0xC3, (byte) 0x28});
        assertFails("Cannot read linked enum file");

        write("e.h", "/* no enum here */\n");
        assertFails("no longer defines 'typedef enum { ... } e_t;'");
        assertTrue(Files.exists(module));
    }

    @Test
    void switchInsideANonModuleOutputResolvesWithoutLinking() throws Exception {
        init();
        write("e.h", "typedef enum { X, Y } e_t;\n");
        write("x.interface.yaml", "kind: interface\nname: x\nfunctions: [{name: f, parameters: [int v]}]\n");
        write("y.interface.yaml", "kind: interface\nname: y\nfunctions: [{name: g}]\n");
        write("ad.adapter.yaml", "kind: adapter\nname: ad\nfrom: x\nto: y\n");
        assertEquals(0, new CliFixture(temporaryDirectory).run("generate"));
        Path source = temporaryDirectory.resolve("ad.c");
        Files.writeString(source, Files.readString(source).replace("/*@Pinfit usercode+ function.x.f.body*/\n    /*@Pinfit usercode-*/",
                "/*@Pinfit usercode+ function.x.f.body*/\n    /*@PinfitSwitch e_t*/\n    switch (v)\n    {\n    }\n    /*@Pinfit usercode-*/"));
        CliFixture confirmed = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, confirmed.run("generate"), confirmed.errors());
        assertTrue(Files.readString(source).contains("case Y:"));
        assertFalse(Files.readString(temporaryDirectory.resolve("ad.adapter.yaml")).contains("externalEnums"));
    }

    // ---- rename ----

    @Test
    void renameRejectsBadAndUnchangedNamesDuplicatesAndOccupiedTargets() throws Exception {
        init();
        write("m.module.yaml", "kind: module\nname: m\n");
        assertFails("New module name must be a valid C identifier", "rename", "module", "m", "bad-name");
        assertFails("Module 'm' is already named 'm'", "rename", "module", "m", "m");
        write("n.h", "occupied\n");
        assertFails("Cannot rename: ", "rename", "module", "m", "n");
        write("sub/m2.module.yaml", "kind: module\nname: m\nheader: other.h\nsource: other.c\n");
        assertFails("Multiple modules named 'm'", "rename", "module", "m", "k");
    }

    @Test
    void renameKeepsCustomFileNamesAndUpdatesOnlyTheName() throws Exception {
        init();
        write("spec.module.yaml", "kind: module\nname: m\nheader: custom.h\nsource: custom.c\n");
        assertEquals(0, cli.run("generate"));
        CliFixture rename = new CliFixture(temporaryDirectory);
        assertEquals(0, rename.run("rename", "module", "m", "k"), rename.errors());
        String yaml = Files.readString(temporaryDirectory.resolve("spec.module.yaml"));
        assertTrue(yaml.contains("name: k") && yaml.contains("header: custom.h"), yaml);
        assertTrue(rename.output().contains("Renamed module 'm' to 'k'"), rename.output());
    }

    @Test
    void renameOfAFlowStyleSpecCannotFindTheNameLine() throws Exception {
        init();
        write("m.module.yaml", "{kind: module, name: m}\n");
        assertFails("Cannot update 'name' in the module spec", "rename", "module", "m", "k");
    }

    @Test
    void renameBeforeFirstGenerateMovesOnlyTheSpec() throws Exception {
        init();
        write("m.module.yaml", "kind: module\nname: m\nheader: m.h\nsource: m.c\n");
        CliFixture rename = new CliFixture(temporaryDirectory);
        assertEquals(0, rename.run("rename", "module", "m", "k"), rename.errors());
        assertTrue(rename.output().contains("Moved m.module.yaml -> k.module.yaml"), rename.output());
    }

    // ---- detach ----

    @Test
    void detachSkipsForeignOutputsAndMissingConfiguration() throws Exception {
        init();
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        Files.writeString(projectFile, Files.readString(projectFile).replace("# file: documentation.yaml", "file: missing.yaml"));
        write("m.module.yaml", "kind: module\nname: m\n");
        write("m.h", "hand written\n");
        CliFixture detach = new CliFixture(temporaryDirectory, Files.readString(projectFile).lines()
                .filter(line -> line.startsWith("name:")).findFirst().orElseThrow().replace("name: ", "").replace("'", "") + "\n");
        assertEquals(0, detach.run("detach"), detach.errors());
        assertEquals("hand written\n", Files.readString(temporaryDirectory.resolve("m.h")));
        assertFalse(detach.output().contains("missing.yaml"), detach.output());
    }

    @Test
    void detachReportsAConfigurationFileItCannotDelete() throws Exception {
        init();
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        Files.writeString(projectFile, Files.readString(projectFile).replace("# file: documentation.yaml", "file: docs"));
        write("docs/keep.txt", "x");
        String name = Files.readString(projectFile).lines().filter(line -> line.startsWith("name:")).findFirst().orElseThrow()
                .replace("name: ", "").replace("'", "");
        CliFixture detach = new CliFixture(temporaryDirectory, name + "\n");
        assertEquals(1, detach.run("detach"));
        assertTrue(detach.errors().contains("Cannot remove Pinfit configuration"), detach.errors());
    }

    // ---- public API overloads and scoped helpers ----

    @Test
    void generatorOverloadsAndScopedHelpers() throws Exception {
        init();
        write("io.interface.yaml", "kind: interface\nname: io\n");
        write("lib/m.module.yaml", "kind: module\nname: m\n");
        write("nested/pinfit.yaml", Files.readString(temporaryDirectory.resolve("pinfit.yaml")));

        YamlFiles yaml = new YamlFiles();
        ProjectService projects = new ProjectService(yaml);
        PinfitGenerator generator = new PinfitGenerator(yaml, new TagHelper(), projects);
        ProjectConfig project = projects.findAndLoad(temporaryDirectory);

        assertTrue(generator.moduleSourceFiles(project, project.root()).isEmpty(), "nothing generated yet");
        assertEquals(3, generator.generate(project, project.root()).size());
        assertEquals(3, generator.generate(project, project.root(), (a, b, c, d, e, f) -> { }).size());
        assertEquals(3, generator.generate(project, project.root(), false, (a, b, c, d, e, f) -> { }).size());
        assertEquals(3, generator.generate(project, project.root(), false, (a, b, c, d, e, f) -> { }, (a, b, c) -> false).size());

        List<Path> cleaned = generator.cleanTags(project, project.root().resolve("lib"));
        assertEquals(2, cleaned.size(), cleaned.toString());
        assertEquals(List.of(project.root().resolve("nested/pinfit.yaml")), generator.findNestedProjectRoots(project.root()));

        PinfitException error = assertThrows(PinfitException.class, () -> generator.renameModule(project, "absent", "x"));
        assertTrue(error.getMessage().contains("No module named 'absent'"));
    }

    @Test
    void generatorOverloadsDeclineUnconfirmedEnumsAndDropWarnings() throws Exception {
        init();
        write("e.h", "typedef enum { X, Y } e_t;\n");
        moduleWithSwitch("m", "e_t", "  - { name: f, return: int, parameters: [int v] }\n", "f");

        YamlFiles yaml = new YamlFiles();
        ProjectService projects = new ProjectService(yaml);
        PinfitGenerator generator = new PinfitGenerator(yaml, new TagHelper(), projects);
        ProjectConfig project = projects.findAndLoad(temporaryDirectory);

        PinfitException declined = assertThrows(PinfitException.class,
                () -> generator.generate(project, project.root(), false, (a, b, c, d, e, f) -> { }));
        assertTrue(declined.getMessage().contains("was not confirmed"), declined.getMessage());
        assertEquals(2, generator.generate(project, project.root(), false, (a, b, c, d, e, f) -> { }, (a, b, c) -> true).size());
    }

    @Test
    void renameReportsASpecItCannotRewrite() throws Exception {
        init();
        Path spec = write("m.module.yaml", "kind: module\nname: m\nheader: m.h\nsource: m.c\n");
        assertTrue(spec.toFile().setWritable(false));
        try {
            assertFails("Cannot write ", "rename", "module", "m", "k");
        } finally {
            temporaryDirectory.resolve("k.module.yaml").toFile().setWritable(true);
            spec.toFile().setWritable(true);
        }
    }

    @Test
    void alsoNestedSkipsGitAndTargetDirectories() throws Exception {
        write(".git/inner/pinfit.yaml", "name: x\nversion: 1\n");
        write("target/inner/pinfit.yaml", "name: x\nversion: 1\n");
        CliFixture nested = new CliFixture(temporaryDirectory, "y\n");
        assertEquals(0, nested.run("generate", "--also-nested"), nested.errors());
        assertTrue(nested.output().contains("Found 0 nested projects."), nested.output());
    }
}
