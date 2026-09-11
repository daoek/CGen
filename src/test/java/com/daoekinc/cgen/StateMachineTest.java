package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateMachineTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesStateMachineNextToYamlAndPreservesUserRegions() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));

        Path specDirectory = Files.createDirectories(temporaryDirectory.resolve("state_machines"));
        Files.writeString(specDirectory.resolve("door.state-machine.yaml"), """
                kind: state-machine
                name: door
                description: Door state machine
                includes: []
                context:
                  - { type: uint32_t, name: open_count, description: Times opened }
                initial: CLOSED
                states:
                  - { name: CLOSED, description: Door is closed }
                  - { name: OPEN, description: Door is open }
                events:
                  - name: OPEN_REQUEST
                    description: Request to open
                    parameters: []
                  - name: CLOSE_REQUEST
                    description: Request to close
                    parameters: []
                transitions:
                  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN, guard: true }
                  - { from: OPEN, event: CLOSE_REQUEST, to: CLOSED, guard: false }
                """);

        assertEquals(0, cli.run("generate"));

        Path headerPath = specDirectory.resolve("door.h");
        Path sourcePath = specDirectory.resolve("door.c");
        assertTrue(Files.isRegularFile(headerPath));
        assertTrue(Files.isRegularFile(sourcePath));

        String header = Files.readString(headerPath);
        assertTrue(header.contains("DOOR_STATE_CLOSED"));
        assertTrue(header.contains("DOOR_STATE_OPEN"));
        assertTrue(header.contains("door_state_t state;"));
        assertTrue(header.contains("uint32_t open_count;"));
        assertTrue(header.contains("void door_init(door_context_t *context);"));
        assertTrue(header.contains("void door_tick(door_context_t *context);"));
        assertTrue(header.contains("void door_go_to_state(door_context_t *context, door_state_t state);"));
        assertTrue(header.contains("void door_on_OPEN_REQUEST(door_context_t *context);"));
        assertTrue(header.contains("void door_on_CLOSE_REQUEST(door_context_t *context);"));

        String source = Files.readString(sourcePath);
        assertTrue(source.contains("static void door_enter_CLOSED(door_context_t *context)"));
        assertTrue(source.contains("static void door_exit_OPEN(door_context_t *context)"));
        assertTrue(source.contains("context->state = DOOR_STATE_CLOSED;"));
        assertTrue(source.contains("door_enter_CLOSED(context);"));
        assertTrue(source.contains("bool cgen_guard = true;"));
        assertTrue(source.contains("/*@CGen usercode+ transition.CLOSED.OPEN_REQUEST.guard*/"));
        assertTrue(source.contains("/*@CGen usercode+ transition.OPEN.CLOSE_REQUEST.action*/"));
        assertFalse(source.contains("transition.OPEN.CLOSE_REQUEST.guard"));
        assertTrue(source.contains("if (!cgen_transitioned)"));
        assertTrue(source.contains("/*@CGen usercode+ event.OPEN_REQUEST.unhandled*/"));
        assertTrue(source.contains("void door_tick(door_context_t *context)"));
        assertTrue(source.contains("/*@CGen usercode+ state.CLOSED.tick*/"));
        assertTrue(source.contains("/*@CGen usercode+ state.OPEN.tick*/"));
        assertTrue(source.contains("void door_go_to_state(door_context_t *context, door_state_t state)"));
        assertTrue(source.contains("door_exit_CLOSED(context);"));
        assertTrue(source.contains("context->state = state;"));
        assertTrue(source.contains("door_enter_OPEN(context);"));

        String customEntry = "    context->open_count++;";
        String updated = source.replace(
                "/*@CGen usercode+ state.OPEN.entry*/\n/*@CGen usercode-*/",
                "/*@CGen usercode+ state.OPEN.entry*/\n" + customEntry + "\n/*@CGen usercode-*/");
        Files.writeString(sourcePath, updated);

        assertEquals(0, cli.run("gen"));
        assertTrue(Files.readString(sourcePath).contains(customEntry));
    }

    @Test
    void tickBodyCanCallGoToStateForConditionTriggeredMoves() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("boot.state-machine.yaml"), """
                kind: state-machine
                name: boot
                initial: WAIT
                states:
                  - { name: WAIT, description: Waiting for the boot timer }
                  - { name: INIT, description: Running one-time init }
                  - { name: RUNNING, description: Normal operation }
                events:
                  - { name: FAULT, description: A fault occurred, parameters: [] }
                transitions:
                  - { from: RUNNING, event: FAULT, to: WAIT }
                """);

        assertEquals(0, cli.run("generate"));

        Path sourcePath = temporaryDirectory.resolve("boot.c");
        String source = Files.readString(sourcePath);
        assertTrue(source.contains("case BOOT_STATE_WAIT:"));
        assertTrue(source.contains("case BOOT_STATE_INIT:"));
        assertTrue(source.contains("case BOOT_STATE_RUNNING:"));

        String customTick = "        if (getMotorSpeed() > 100.0f)\n        {\n            boot_go_to_state(context, BOOT_STATE_WAIT);\n        }";
        String updated = source.replace(
                "/*@CGen usercode+ state.RUNNING.tick*/\n/*@CGen usercode-*/",
                "/*@CGen usercode+ state.RUNNING.tick*/\n" + customTick + "\n/*@CGen usercode-*/");
        Files.writeString(sourcePath, updated);

        assertEquals(0, cli.run("gen"));
        assertTrue(Files.readString(sourcePath).contains(customTick));
    }
}
