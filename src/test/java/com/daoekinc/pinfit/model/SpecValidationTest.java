package com.daoekinc.pinfit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every validation error each {@code *Spec.from} can raise, driven straight through the parser
 * (no generator, no files) - one row per rejected YAML shape, asserting the message a user sees.
 */
class SpecValidationTest {
    private static final Path SOURCE = Path.of("spec.yaml");

    private static final Map<String, BiFunction<Path, Map<String, Object>, Object>> PARSERS = Map.of(
            "interface", InterfaceSpec::from,
            "module", ModuleSpec::from,
            "state-machine", StateMachineSpec::from,
            "observer", ObserverSpec::from,
            "command-table", CommandTableSpec::from,
            "status-codes", StatusCodesSpec::from,
            "adapter", AdapterSpec::from);

    static Stream<Arguments> rejectedSpecs() {
        return Stream.of(
                // ---- interface ----
                Arguments.of("interface", "kind: module\nname: a", ".kind must be interface"),
                Arguments.of("interface", "kind: interface\nname: a\nstructs: [{name: s, fields: []}]", "struct s needs at least one field"),
                Arguments.of("interface", "kind: interface\nname: a\nenums: [{name: e, values: []}]", "enum e needs at least one value"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: int, invalidReturn: 'x;'}]",
                        "must be a safe one-line C expression"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: int, invalidReturn: ' '}]",
                        "must be a safe one-line C expression"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: int, invalidReturn: \"a\\nb\"}]",
                        "must be a safe one-line C expression"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: int, invalidReturn: \"a\\rb\"}]",
                        "must be a safe one-line C expression"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: ' '}]", "must be a safe one-line C declaration fragment"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: \"a\\nb\"}]", "must be a safe one-line C declaration fragment"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: \"a\\rb\"}]", "must be a safe one-line C declaration fragment"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: 'a;'}]", "must be a safe one-line C declaration fragment"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: 'a{'}]", "must be a safe one-line C declaration fragment"),
                Arguments.of("interface", "kind: interface\nname: a\nfunctions: [{name: f, return: 'a}'}]", "must be a safe one-line C declaration fragment"),
                // ---- module ----
                Arguments.of("module", "kind: interface\nname: a", ".kind must be module"),
                Arguments.of("module", "kind: module\nname: a\nvariables: [{type: int, name: v, visibility: nope}]",
                        ".visibility must be public, private, get, or set"),
                Arguments.of("module", "kind: module\nname: a\nvariables: [{type: int, name: v, initial: \"1\\n\"}]", ".initial must be a one-line C expression"),
                Arguments.of("module", "kind: module\nname: a\nvariables: [{type: int, name: v, initial: \"1\\r\"}]", ".initial must be a one-line C expression"),
                Arguments.of("module", "kind: module\nname: a\nvariables: [{type: int, name: v, initial: '1;'}]", ".initial must be a one-line C expression"),
                Arguments.of("module", "kind: module\nname: a\nfunctions: [{name: f, visibility: nope}]", ".visibility must be public or private"),
                Arguments.of("module", "kind: module\nname: a\nfunctions: [{name: f, visibility: get}]", ".visibility must be public or private"),
                // ---- state machine, builtin ----
                Arguments.of("state-machine", "kind: state-machine\nengine: other\nname: a", ".engine must be 'builtin' or 'statesmith'"),
                Arguments.of("state-machine", "kind: module\nname: a", ".kind must be state-machine"),
                Arguments.of("state-machine", "kind: state-machine\nname: a\ninitial: A", ".states needs at least one state"),
                Arguments.of("state-machine", "kind: state-machine\nname: a\ninitial: B\nstates: [{name: A}]", ".initial references unknown state 'B'"),
                Arguments.of("state-machine", "kind: state-machine\nname: a\ninitial: A\nstates: [{name: A}]\nevents: [{name: E}]\n"
                        + "transitions: [{from: B, event: E, to: A}]", ".from references unknown state 'B'"),
                Arguments.of("state-machine", "kind: state-machine\nname: a\ninitial: A\nstates: [{name: A}]\nevents: [{name: E}]\n"
                        + "transitions: [{from: A, event: X, to: A}]", ".event references unknown event 'X'"),
                // ---- state machine, statesmith ----
                Arguments.of("state-machine", "kind: module\nengine: statesmith\nname: a", ".kind must be state-machine"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A", ".states needs at least one state"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A, states: []}]",
                        ".states needs at least one state when present"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A}, {name: A}]",
                        ".states contains duplicate name 'A'"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A}]\nevents: [{name: tick}]",
                        "it is reserved for the polled do-event"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: C\nstates: [{name: P, initial: C, states: [{name: C}]}]",
                        ".initial references unknown top-level state 'C'"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A}]\n"
                        + "transitions: [{from: B, event: tick, to: A}]", ".from references unknown state 'B'"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A}]\n"
                        + "transitions: [{from: A, event: tick, to: B}]", ".to references unknown state 'B'"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: A\nstates: [{name: A}]\n"
                        + "transitions: [{from: A, event: X, to: A}]", ".event references unknown event 'X'"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: P\nstates: [{name: P, states: [{name: C}]}]",
                        "composite state 'P' is entered directly"),
                Arguments.of("state-machine", "kind: state-machine\nengine: statesmith\nname: a\ninitial: P\nstates: [{name: P, initial: Z, states: [{name: C}]}]",
                        "state 'P'.initial references 'Z', which is not one of its direct children"),
                // ---- observer ----
                Arguments.of("observer", "kind: module\nname: a", ".kind must be observer"),
                Arguments.of("observer", "kind: observer\nname: a\ninterface: i\ncapacity: 0", ".capacity must be at least 1"),
                // ---- status codes ----
                Arguments.of("status-codes", "kind: module\nname: a", ".kind must be status-codes"),
                Arguments.of("status-codes", "kind: status-codes\nname: a\ncodes: [{name: OK}]", ".value is required"),
                Arguments.of("status-codes", "kind: status-codes\nname: a\ncodes: [{name: OK, value: zero}]", ".value must be an integer"),
                Arguments.of("status-codes", "kind: status-codes\nname: a", ".codes needs at least one code"),
                // ---- command table ----
                Arguments.of("command-table", "kind: module\nname: a", ".kind must be command-table"),
                Arguments.of("command-table", "kind: command-table\nname: a", ".commands needs at least one command"),
                Arguments.of("command-table", "kind: command-table\nname: a\ncommands: [{name: A, opcode: 1}, {name: B}]",
                        "must either give every command an explicit opcode or none at all"),
                Arguments.of("command-table", "kind: command-table\nname: a\ncommands: [{name: A, opcode: one}]", ".opcode must be an integer"),
                // ---- adapter ----
                Arguments.of("adapter", "kind: module\nname: a", ".kind must be adapter"),
                Arguments.of("adapter", "kind: adapter\nname: a\nfrom: x\nto: x", ".from and .to must reference different interfaces"));
    }

    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("rejectedSpecs")
    void rejectsInvalidSpec(String kind, String yaml, String expectedMessage) {
        PinfitException error = assertThrows(PinfitException.class, () -> parse(kind, yaml));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    @Test
    void parsesIntegerValuesGivenAsText() {
        StatusCodesSpec codes = (StatusCodesSpec) parse("status-codes", "kind: status-codes\nname: a\ncodes: [{name: OK, value: '0'}]");
        assertEquals(0, codes.codes().get(0).value());

        CommandTableSpec table = (CommandTableSpec) parse("command-table",
                "kind: command-table\nname: a\ncommands: [{name: A, opcode: '7'}]");
        assertEquals(7, table.commands().get(0).opcode());
    }

    @Test
    void resolvesEffectiveInitialLeafThroughNestedComposites() {
        StateMachineSpec machine = (StateMachineSpec) parse("state-machine", """
                kind: state-machine
                engine: statesmith
                name: a
                initial: P
                states:
                  - name: P
                    initial: Q
                    states:
                      - name: Q
                        initial: LEAF
                        states: [{name: LEAF}]
                """);
        assertEquals("LEAF", machine.effectiveInitialLeaf().name());
        assertEquals(List.of("LEAF"), machine.leafStates().stream().map(StateMachineSpec.State::name).toList());
    }

    private static Object parse(String kind, String yaml) {
        Map<String, Object> map = new Yaml().load(yaml);
        return PARSERS.get(kind).apply(SOURCE, map);
    }
}
