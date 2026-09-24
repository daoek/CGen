package com.daoekinc.pinfit.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.model.StateMachineSpec;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Package-private rendering helpers, exercised directly for inputs the generator never produces. */
class RenderSupportTest {
    private static final ProjectConfig PROJECT = new ProjectConfig(Path.of("."), "demo", "0.1.0",
            new ProjectConfig.Documentation("doxygen", null), 4, "\n", "extern", true, false,
            new ProjectConfig.StateSmithSettings("ss.cli", "0.22.2"), false);

    @Test
    void macroAppendsATrailingUnderscoreOnlyWhenMissing() {
        assertEquals("DOOR_H_", RenderSupport.macro("door.h"));
        assertEquals("DOOR_", RenderSupport.macro("door_"));
        assertEquals("DOOR_", RenderSupport.macro("door."));
    }

    @Test
    void plantUmlDeclaresCompositeWithoutOwnInitialWhenOnlyItsChildrenAreEntered() {
        Map<String, Object> yaml = new Yaml().load("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CHILD
                states:
                  - { name: CHILD }
                  - name: GROUP
                    states: [{name: INNER}]
                transitions:
                  - { from: CHILD, event: tick, to: INNER }
                """);
        StateMachineSpec machine = StateMachineSpec.from(Path.of("door.state-machine.yaml"), yaml);

        String plantuml = new StateSmithPlantUmlRenderer().render(PROJECT, machine);

        assertTrue(plantuml.contains("state GROUP {\n  state INNER\n}\n"), plantuml);
    }
}
