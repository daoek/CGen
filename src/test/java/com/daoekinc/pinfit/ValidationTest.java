package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationTest {
    @TempDir
    Path temporaryDirectory;

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
    void reportsParameterYamlSyntax() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("broken.interface.yaml"), """
                kind: interface
                name: common_iic
                functions:
                  - name: SendData
                    return: int
                    parameters: [42]
                """);

        assertEquals(1, cli.run("generate"));
        String error = cli.errors();
        assertTrue(error.contains("broken.interface.yaml.functions[0].parameters[0]"));
        assertTrue(error.contains("\u001B[1;31mPinfit error\u001B[0m"));
        assertTrue(error.contains("\u001B[1;36mExample YAML\u001B[0m"));
        assertTrue(error.contains("""
                parameters:
                  - uint8_t *buffer
                  - uint32_t len"""));
    }

    @Test
    void rejectsStateMachineTransitionToUnknownState() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("door.state-machine.yaml"), """
                kind: state-machine
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                events:
                  - { name: OPEN_REQUEST, parameters: [] }
                transitions:
                  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN }
                """);

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains(".to references unknown state 'OPEN'"));
    }

    @Test
    void rejectsDuplicateStateMachineTransitionForSameStateAndEvent() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("door.state-machine.yaml"), """
                kind: state-machine
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - { name: OPEN }
                events:
                  - { name: OPEN_REQUEST, parameters: [] }
                transitions:
                  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN }
                  - { from: CLOSED, event: OPEN_REQUEST, to: CLOSED }
                """);

        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("duplicate name 'CLOSED/OPEN_REQUEST'"));
    }

    @Test
    void rejectsStateMachineTransitionWithoutEvent() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("boot.state-machine.yaml"), """
                kind: state-machine
                name: boot
                initial: WAIT
                states:
                  - { name: WAIT }
                  - { name: INIT }
                events: []
                transitions:
                  - { from: WAIT, to: INIT }
                """);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains(".transitions[0].event is required"));
    }

    @Test
    void rejectsAdapterMappingWithMismatchedSignatures() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("bus.interface.yaml"), """
                kind: interface
                name: bus
                invalidReturn: -1
                functions:
                  - { name: write, return: int, parameters: [{ type: uint32_t, name: value }] }
                """);
        Files.writeString(temporaryDirectory.resolve("bus_hal.interface.yaml"), """
                kind: interface
                name: bus_hal
                invalidReturn: -1
                functions:
                  - { name: send, return: void, parameters: [] }
                """);
        Files.writeString(temporaryDirectory.resolve("bus_adapter.adapter.yaml"), """
                kind: adapter
                name: bus_adapter
                includes: []
                from: bus
                to: bus_hal
                context: []
                mappings:
                  - { from: write, to: send }
                """);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("mismatched"));
    }

    @Test
    void rejectsObserverListenerInterfaceWithNonVoidFunction() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("bus_hal.interface.yaml"), """
                kind: interface
                name: bus_hal
                invalidReturn: -1
                functions:
                  - { name: send, return: int, parameters: [] }
                """);
        Files.writeString(temporaryDirectory.resolve("events.observer.yaml"), """
                kind: observer
                name: events
                includes: []
                interface: bus_hal
                context: []
                """);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("must return void"));
    }

    @Test
    void rejectsCommandTableWithMixedExplicitAndImplicitOpcodes() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("uart_cmd.command-table.yaml"), """
                kind: command-table
                name: uart_cmd
                includes: []
                context: []
                commands:
                  - { name: PING, opcode: 0 }
                  - { name: RESET }
                """);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("must either give every command an explicit opcode or none at all"));
    }

    @Test
    void rejectsStatusCodesWithoutSuccessCode() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("pinfit_status.status-codes.yaml"), """
                kind: status-codes
                name: pinfit_status
                includes: []
                codes:
                  - { name: INVALID_PARAM, value: -1 }
                """);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains("exactly one code with value 0"));
    }

    @Test
    void typeKeyedReturnDefaultsBeatScalarDefault() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("flash.interface.yaml"), """
                kind: interface
                name: flash
                invalidReturn: -1
                uninitializedReturn: -2
                invalidReturns:
                  flash_command_t: FLASH_COMMAND_NONE
                uninitializedReturns:
                  flash_command_t: FLASH_COMMAND_UNKNOWN
                enums:
                  - name: flash_command_t
                    values:
                      - { name: FLASH_COMMAND_NONE }
                      - { name: FLASH_COMMAND_UNKNOWN }
                functions:
                  - name: generate_command
                    return: flash_command_t
                    parameters: []
                  - name: read_status
                    return: int32_t
                    parameters: []
                """);

        assertEquals(0, cli.run("generate"));

        String source = Files.readString(temporaryDirectory.resolve("flash_I.h"));
        assertTrue(source.contains("flash_command_t pinfit_result = FLASH_COMMAND_NONE;"));
        assertTrue(source.contains("pinfit_result = FLASH_COMMAND_UNKNOWN;"));
        assertTrue(source.contains("int32_t pinfit_result = -1;"));
        assertTrue(source.contains("pinfit_result = -2;"));
    }

    @Test
    void structReturnUsesCompoundLiteralDefaults() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("flash.interface.yaml"), """
                kind: interface
                name: flash
                invalidReturn: -1
                invalidReturns:
                  flash_command_t: '(flash_command_t){0}'
                includes: [<stdint.h>]
                structs:
                  - name: flash_command_t
                    fields:
                      - uint8_t opcode
                      - uint32_t address
                functions:
                  - name: generate_command
                    return: flash_command_t
                    parameters: []
                  - name: describe_command
                    return: flash_status_t
                    parameters: []
                """);

        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("flash_I.h"));
        assertTrue(header.contains("flash_command_t pinfit_result = (flash_command_t){0};"));
        assertTrue(header.contains("flash_status_t pinfit_result = -1;"));
    }

    @Test
    void zeroInitializesInterfaceFunctionWithoutInvalidReturn() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("status.interface.yaml"), """
                kind: interface
                name: status
                functions:
                  - name: read
                    return: uint32_t
                    parameters: []
                """);

        assertEquals(0, cli.run("generate"));
        assertTrue(Files.readString(temporaryDirectory.resolve("status_I.h")).contains("(uint32_t){0}"));
    }
}
