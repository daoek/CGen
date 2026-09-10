package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternGeneratorsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesSingletonAccessorForModule() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("logger.module.yaml"), """
                kind: module
                name: logger
                implements: []
                includes: []
                context: []
                variables: []
                singleton: true
                """);
        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("logger.h"));
        String source = Files.readString(temporaryDirectory.resolve("logger.c"));
        assertTrue(header.contains("logger_context_t *logger_instance(void);"));
        assertTrue(source.contains("static logger_context_t logger_singleton_context;"));
        assertTrue(source.contains("if (!logger_singleton_initialized)"));
        assertTrue(source.contains("return &logger_singleton_context;"));
    }

    @Test
    void generatesStatusCodesHeaderWithCheckingMacros() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("cgen_status.status-codes.yaml"), """
                kind: status-codes
                name: cgen_status
                includes: []
                codes:
                  - { name: OK, value: 0 }
                  - { name: INVALID_PARAM, value: -1 }
                """);
        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("cgen_status.h"));
        assertTrue(header.contains("CGEN_STATUS_OK = 0,"));
        assertTrue(header.contains("CGEN_STATUS_INVALID_PARAM = -1"));
        assertTrue(header.contains("#define CGEN_STATUS_SUCCEEDED(status) ((status) == CGEN_STATUS_OK)"));
        assertTrue(header.contains("#define CGEN_STATUS_FAILED(status) (!CGEN_STATUS_SUCCEEDED(status))"));
        assertTrue(header.contains("#define CGEN_STATUS_CHECK(status_expression)"));
    }

    @Test
    void generatesObserverSubscribeAndPublish() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("button_listener.interface.yaml"), """
                kind: interface
                name: button_listener
                invalidReturn: -1
                functions:
                  - name: on_click
                    return: void
                    parameters:
                      - { type: uint32_t, name: x }
                """);
        Files.writeString(temporaryDirectory.resolve("button_events.observer.yaml"), """
                kind: observer
                name: button_events
                includes: []
                interface: button_listener
                capacity: 4
                context: []
                """);
        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("button_events/button_events.h"));
        String source = Files.readString(temporaryDirectory.resolve("button_events/button_events.c"));
        assertTrue(header.contains("const button_listener_interface_t *subscribers[BUTTON_EVENTS_CAPACITY];"));
        assertTrue(header.contains("bool button_events_subscribe(button_events_context_t *context, const button_listener_interface_t *subscriber);"));
        assertTrue(header.contains("void button_events_publish_on_click(button_events_context_t *context, uint32_t x);"));
        assertTrue(source.contains("button_listener_on_click(context->subscribers[index], x);"));
    }

    @Test
    void generatesCommandTableDispatch() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("uart_cmd.command-table.yaml"), """
                kind: command-table
                name: uart_cmd
                includes: []
                context: []
                commands:
                  - { name: PING, opcode: 0 }
                  - { name: RESET, opcode: 1 }
                """);
        assertEquals(0, cli.run("generate"));

        String header = Files.readString(temporaryDirectory.resolve("uart_cmd/uart_cmd.h"));
        String source = Files.readString(temporaryDirectory.resolve("uart_cmd/uart_cmd.c"));
        assertTrue(header.contains("UART_CMD_CMD_PING = 0"));
        assertTrue(header.contains("UART_CMD_CMD_RESET = 1"));
        assertTrue(source.contains("case UART_CMD_CMD_PING:"));
        assertTrue(source.contains("uart_cmd_handle_PING(context, payload, length);"));
        assertTrue(source.contains("/*@CGen(+command.unknown)*/"));
    }

    @Test
    void generatesAdapterCallThroughForMappedFunctionAndStubForUnmapped() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("bus.interface.yaml"), """
                kind: interface
                name: bus
                invalidReturn: -1
                functions:
                  - name: write
                    return: int
                    parameters:
                      - { type: const uint8_t *, name: data }
                      - { type: uint32_t, name: length }
                  - name: reset
                    return: void
                    parameters: []
                """);
        Files.writeString(temporaryDirectory.resolve("bus_hal.interface.yaml"), """
                kind: interface
                name: bus_hal
                invalidReturn: -1
                functions:
                  - name: send
                    return: int
                    parameters:
                      - { type: const uint8_t *, name: data }
                      - { type: uint32_t, name: length }
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
        assertEquals(0, cli.run("generate"));

        String source = Files.readString(temporaryDirectory.resolve("bus_adapter/bus_adapter.c"));
        assertTrue(source.contains("cgen_result = bus_hal_send(adapter->target, data, length);"));
        assertTrue(source.contains("/*@CGen(+function.bus.reset.body)*/"));
        assertTrue(source.contains("interface->write = bus_adapter_bus_write;"));
        assertTrue(source.contains("interface->reset = bus_adapter_bus_reset;"));
    }
}
