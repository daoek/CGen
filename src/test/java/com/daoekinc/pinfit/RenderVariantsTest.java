package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generator output for spec shapes and project settings the scenario tests don't reach: context
 * fields on every pattern, parameterised and mapped-void adapter functions, singleton modules,
 * accessor variants, and the {@code none} documentation style with camelCase names.
 */
class RenderVariantsTest {
    @TempDir
    Path temporaryDirectory;

    private void write(String file, String content) throws Exception {
        Files.writeString(temporaryDirectory.resolve(file), content);
    }

    private String read(String file) throws Exception {
        return Files.readString(temporaryDirectory.resolve(file));
    }

    @Test
    void patternsWithContextFieldsAndParameters() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        write("bus.interface.yaml", """
                kind: interface
                name: bus
                invalidReturn: -1
                functions:
                  - name: write
                    return: int
                    parameters:
                      - { type: uint32_t, name: length, description: Bytes to write }
                  - name: reset
                  - name: log
                    parameters: [uint32_t level]
                """);
        write("bus_hal.interface.yaml", """
                kind: interface
                name: bus_hal
                invalidReturn: -1
                functions:
                  - name: send
                    return: int
                    parameters: [uint32_t length]
                  - name: stop
                """);
        write("bus_adapter.adapter.yaml", """
                kind: adapter
                name: bus_adapter
                from: bus
                to: bus_hal
                context: [uint32_t calls]
                mappings:
                  - { from: write, to: send }
                  - { from: reset, to: stop }
                """);
        write("listener.interface.yaml", """
                kind: interface
                name: listener
                functions: [{name: on_event}]
                """);
        write("events.observer.yaml", """
                kind: observer
                name: events
                interface: listener
                context: [uint32_t published]
                """);
        write("commands.command-table.yaml", """
                kind: command-table
                name: commands
                context: [uint32_t handled]
                commands:
                  - { name: PING }
                  - { name: RESET }
                """);
        write("door.state-machine.yaml", """
                kind: state-machine
                name: door
                initial: CLOSED
                states: [{name: CLOSED}, {name: OPEN}]
                events: [{name: OPEN_REQUEST, parameters: [uint32_t speed]}]
                transitions: [{from: CLOSED, event: OPEN_REQUEST, to: OPEN}]
                """);
        write("driver.module.yaml", """
                kind: module
                name: driver
                implements: [bus]
                singleton: true
                functions:
                  - { name: helper, parameters: [uint32_t value] }
                """);
        write("startup.module.yaml", "kind: module\nname: startup\nsingleton: true\n");
        write("settings.module.yaml", "kind: module\nname: settings\nsingleton: true\ncontext: [uint32_t value]\n");
        assertEquals(0, cli.run("generate"), cli.errors());

        assertTrue(!read("startup.h").contains("startup_context_t"), "a run-once singleton has no context");
        assertTrue(read("settings.h").contains("    uint32_t value;\n"));

        String adapterHeader = read("bus_adapter.h");
        String adapterSource = read("bus_adapter.c");
        assertTrue(adapterHeader.contains("    uint32_t calls;\n"), adapterHeader);
        assertTrue(adapterSource.contains("    bus_hal_stop(adapter->target);\n"), adapterSource);
        assertTrue(adapterSource.contains("    (void)level;\n"), adapterSource);

        assertTrue(read("events.h").contains("    uint32_t published;\n"));
        String commands = read("commands.h");
        assertTrue(commands.contains("    uint32_t handled;\n"), commands);
        assertTrue(commands.contains("COMMANDS_CMD_RESET = 1"), commands);

        assertTrue(read("door.c").contains("    (void)speed;\n"));
        assertTrue(read("bus_I.h").contains(" * @param length Bytes to write\n"));

        String driverHeader = read("driver.h");
        String driverSource = read("driver.c");
        assertTrue(driverHeader.contains("    unsigned char reserved;\n"), driverHeader);
        assertTrue(driverSource.contains("static void helper(uint32_t value);"), driverSource);
        assertTrue(driverSource.contains("    int pinfit_result = -1;\n"), driverSource);
    }

    @Test
    void noDocumentationCamelCaseAccessorsAndNoSilencers() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        Files.writeString(projectFile, Files.readString(projectFile)
                .replace("style: doxygen", "style: none")
                .replace("# suppressUnusedWarnings: false", "suppressUnusedWarnings: false")
                .replace("# publicVariables: accessors", "publicVariables: accessors")
                .replace("# functionNaming: camelCase", "functionNaming: camelCase"));
        write("io.interface.yaml", """
                kind: interface
                name: io
                functions:
                  - { name: read, return: int, parameters: [uint32_t count] }
                """);
        write("mod__x.module.yaml", """
                kind: module
                name: mod__x
                implements: [io]
                context: [uint32_t state]
                variables:
                  - uint32_t hidden
                  - char *label get
                """);
        write("door.state-machine.yaml", """
                kind: state-machine
                name: door
                initial: CLOSED
                states: [{name: CLOSED}]
                """);
        assertEquals(0, cli.run("generate"), cli.errors());

        String header = read("mod__x.h");
        String source = read("mod__x.c");
        assertFalse(header.contains("/**"), header);
        assertFalse(read("io_I.h").contains("/**"));
        assertFalse(read("door.h").contains("/**"));
        assertTrue(header.contains("char *getLabel(void);"), header);
        assertFalse(header.contains("Hidden"), "private variables get no accessors");
        assertFalse(source.contains("(void)count;"), "suppressUnusedWarnings: false must not emit silencers");
        assertTrue(source.contains("void modXBindIo("), source);
    }
}
