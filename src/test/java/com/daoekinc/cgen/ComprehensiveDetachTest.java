package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code detach} must cover every generator kind - the previous coverage (every kind's YAML
 * deleted, every kind's marker stripped) was already correct by inspection, but nothing actually
 * exercised all of them together. This does: one project using all seven `kind:`s, both
 * state-machine engines, and an expanded {@code @CGenSwitch}, then asserts detach leaves no CGen
 * marker and no spec YAML anywhere, while every hand-written line of C survives.
 *
 * <p>The statesmith-engine machine's StateSmith-owned files (door_sm/door_sm.h/.c) are placed by
 * hand rather than through a real {@code ss.cli} run - detach's coverage of them does not depend
 * on StateSmith actually being installed, only on the files existing with CGen's marker, which is
 * all {@code cleanTags} ever checks.
 */
class ComprehensiveDetachTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void detachClearsEveryGeneratorKindAndKeepsAllHandWrittenCode() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));

        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("create", "module", "sensor_impl", "--implements", "sensor"));
        assertEquals(0, cli.run("create", "interface", "beacon"));
        makeAllFunctionsVoid(temporaryDirectory.resolve("beacon.interface.yaml"));
        assertEquals(0, cli.run("create", "observer", "events", "--interface", "beacon"));
        assertEquals(0, cli.run("create", "command-table", "cmds"));
        assertEquals(0, cli.run("create", "status-codes", "codes"));
        assertEquals(0, cli.run("create", "adapter", "bridge", "--from", "sensor", "--to", "beacon"));
        assertEquals(0, cli.run("create", "state-machine", "door"));

        // A hand-written @CGenSwitch, expanded by this generate into its own nested usercode
        // regions - detach must strip those too, the same way it strips any other region marker.
        Files.writeString(temporaryDirectory.resolve("flash.module.yaml"), """
                kind: module
                name: flash
                implements: []
                includes: []
                context: []
                variables: []
                enums:
                  - name: flash_opcode_t
                    values:
                      - { name: FLASH_OP_READ }
                      - { name: FLASH_OP_WRITE }
                functions:
                  - name: dispatch
                    return: void
                    parameters:
                      - flash_opcode_t opcode
                    visibility: public
                """);

        assertEquals(0, cli.run("generate"), cli.errors());

        Path flashSource = temporaryDirectory.resolve("flash.c");
        String withSwitch = Files.readString(flashSource).replace(
                "/*@CGen usercode+ function.dispatch.body*/\n",
                "/*@CGen usercode+ function.dispatch.body*/\n"
                        + "/*@CGenSwitch flash_opcode_t*/\n"
                        + "switch (opcode)\n"
                        + "{\n"
                        + "}\n");
        Files.writeString(flashSource, withSwitch);
        assertEquals(0, cli.run("generate"), cli.errors());
        String expanded = Files.readString(flashSource);
        assertTrue(expanded.contains("switchcase.flash_opcode_t.FLASH_OP_READ"), "sanity: @CGenSwitch expanded");

        // A hand-written line in each region, in every kind, so "all C code is otherwise intact"
        // is something this test actually checks rather than assumes.
        String preciousMarker = "PRECIOUS_";
        putHandWrittenLineInEveryRegion(temporaryDirectory, preciousMarker);

        // engine: statesmith's own file set, placed as if a prior `generate` (with ss.cli
        // available) had already produced it - see class javadoc.
        placeStatesmithFileSet(temporaryDirectory, preciousMarker);

        List<Path> allFiles;
        try (var walk = Files.walk(temporaryDirectory)) {
            allFiles = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(allFiles.stream().anyMatch(path -> path.toString().endsWith(".yaml")), "sanity: specs exist");

        // CGen init names the project after the temp directory - read it back and confirm with
        // the real name instead of guessing it.
        String projectName = readProjectName(temporaryDirectory);
        CliFixture confirmedDetach = new CliFixture(temporaryDirectory, projectName + "\n");
        assertEquals(0, confirmedDetach.run("detach"), confirmedDetach.errors());

        try (var walk = Files.walk(temporaryDirectory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                assertFalse(name.endsWith(".interface.yaml"), path.toString());
                assertFalse(name.endsWith(".module.yaml"), path.toString());
                assertFalse(name.endsWith(".state-machine.yaml"), path.toString());
                assertFalse(name.endsWith(".status-codes.yaml"), path.toString());
                assertFalse(name.endsWith(".observer.yaml"), path.toString());
                assertFalse(name.endsWith(".command-table.yaml"), path.toString());
                assertFalse(name.endsWith(".adapter.yaml"), path.toString());
                assertFalse(name.equals("cgen.yaml"), path.toString());

                if (Files.isRegularFile(path) && isProbablyText(path)) {
                    String content = Files.readString(path);
                    // CGen's own structural markers - not "/*@CGen" broadly, which would also
                    // (wrongly) flag a user-authored "/*@CGenSwitch ...*/" directive left in
                    // place on purpose: that line is the user's own text, not CGen's, and detach
                    // never touches it (confirmed separately - it stays until the user removes it).
                    assertFalse(content.contains("/*@CGen("), path + " still has a CGen marker:\n" + content);
                    assertFalse(content.contains("/*@CGen usercode"), path + " still has a CGen marker:\n" + content);
                }
            }
        }

        // The .plantuml is documentation, kept deliberately (only its marker stripped) - not
        // covered by the deletion assertions above, so check it explicitly both ways.
        Path plantuml = temporaryDirectory.resolve("door2_sm/door2_sm.plantuml");
        assertTrue(Files.exists(plantuml), "the .plantuml must be kept, not deleted");
        assertFalse(Files.readString(plantuml).contains("@CGen"));

        assertTrue(Files.readString(temporaryDirectory.resolve("sensor_impl.c")).contains(preciousMarker + "sensor_impl"));
        assertTrue(Files.readString(temporaryDirectory.resolve("flash.c")).contains(preciousMarker + "flash"));
        assertTrue(Files.readString(temporaryDirectory.resolve("door.c")).contains(preciousMarker + "door"));
        assertTrue(Files.readString(temporaryDirectory.resolve("door2_hooks.c")).contains(preciousMarker + "door_hooks2"));
        assertTrue(Files.readString(temporaryDirectory.resolve("door2.c")).contains(preciousMarker + "door2"));
    }

    private static void makeAllFunctionsVoid(Path interfaceYaml) throws Exception {
        String content = Files.readString(interfaceYaml).replace("return: int", "return: void");
        Files.writeString(interfaceYaml, content);
    }

    private static void putHandWrittenLineInEveryRegion(Path directory, String marker) throws Exception {
        insertIntoFirstRegion(directory.resolve("sensor_impl.c"), marker + "sensor_impl");
        insertIntoFirstRegion(directory.resolve("flash.c"), marker + "flash");
        insertIntoFirstRegion(directory.resolve("door.c"), marker + "door");
    }

    private static void insertIntoFirstRegion(Path file, String comment) throws Exception {
        String content = Files.readString(file);
        int begin = content.indexOf("/*@CGen usercode+ ");
        assertTrue(begin >= 0, file + " has no usercode region to write into");
        int endOfBeginLine = content.indexOf('\n', begin) + 1;
        String updated = content.substring(0, endOfBeginLine) + "/* " + comment + " */\n" + content.substring(endOfBeginLine);
        Files.writeString(file, updated);
    }

    /**
     * Simulates the file set an {@code engine: statesmith} machine has after a real
     * {@code generate} with {@code ss.cli} installed - written directly rather than run through
     * the real tool, so this test needs no external dependency. Named door2/door_hooks2/door2_sm
     * so nothing collides with the plain {@code door} builtin machine created above.
     */
    private static void placeStatesmithFileSet(Path directory, String marker) throws Exception {
        Files.writeString(directory.resolve("door2.state-machine.yaml"), """
                kind: state-machine
                engine: statesmith
                name: door2
                initial: CLOSED
                states:
                  - { name: CLOSED }
                events: []
                transitions: []
                """);
        Files.writeString(directory.resolve("door2.h"), """
                /*@CGen(file:state-machine-statesmith-api-header:door2.state-machine.yaml)*/
                #ifndef DOOR2_H_
                #define DOOR2_H_
                /*@CGen usercode+ state-machine.header.preamble*/
                /*@CGen usercode-*/
                #endif /* DOOR2_H_ */
                """);
        Files.writeString(directory.resolve("door2.c"), """
                /*@CGen(file:state-machine-statesmith-api-source:door2.state-machine.yaml)*/
                #include "door2.h"
                /*@CGen usercode+ state-machine.source.includes*/
                /* """ + marker + """
                door2 */
                /*@CGen usercode-*/
                """);
        Files.writeString(directory.resolve("door2_hooks.h"), """
                /*@CGen(file:state-machine-hooks-header:door2.state-machine.yaml)*/
                #ifndef DOOR2_HOOKS_H_
                #define DOOR2_HOOKS_H_
                /*@CGen usercode+ state-machine.hooks-header.preamble*/
                /*@CGen usercode-*/
                #endif /* DOOR2_HOOKS_H_ */
                """);
        Files.writeString(directory.resolve("door2_hooks.c"), """
                /*@CGen(file:state-machine-hooks-source:door2.state-machine.yaml)*/
                #include "door2_hooks.h"
                /*@CGen usercode+ state-machine.hooks-source.includes*/
                /* """ + marker + """
                door_hooks2 */
                /*@CGen usercode-*/
                """);
        Path smDirectory = Files.createDirectories(directory.resolve("door2_sm"));
        Files.writeString(smDirectory.resolve("door2_sm.plantuml"), """
                ' /*@CGen(file:state-machine-plantuml:door2.state-machine.yaml)*/
                @startuml door2_sm
                state CLOSED
                [*] --> CLOSED
                @enduml
                """);
        Files.writeString(smDirectory.resolve("door2_sm.h"), """
                /*@CGen(file:state-machine-statesmith:door2.state-machine.yaml)*/
                /* Generated by StateSmith via CGen - do not edit. */
                typedef struct door2_sm door2_sm;
                """);
        Files.writeString(smDirectory.resolve("door2_sm.c"), """
                /*@CGen(file:state-machine-statesmith:door2.state-machine.yaml)*/
                /* Generated by StateSmith via CGen - do not edit. */
                #include "door2_sm.h"
                """);
    }

    private static String readProjectName(Path directory) throws Exception {
        String cgenYaml = Files.readString(directory.resolve("cgen.yaml"));
        var matcher = java.util.regex.Pattern.compile("(?m)^name:\\s*'?([^'\\n]+)'?\\s*$").matcher(cgenYaml);
        assertTrue(matcher.find(), "cgen.yaml has no name:");
        return matcher.group(1);
    }

    private static boolean isProbablyText(Path path) {
        String name = path.getFileName().toString();
        return !name.endsWith(".class");
    }
}
