package com.daoekinc.pinfit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.config.YamlFiles;
import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.model.StateMachineSpec;
import com.daoekinc.pinfit.tag.TagHelper;
import com.daoekinc.pinfit.tag.TagHelper.UserRegions;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage for the {@code engine: statesmith} state machine - validation, and the
 * plantuml/hooks/api renderers directly (never through {@code PinfitGenerator.generate()}, so none of
 * this needs {@code ss.cli} installed). The full round trip through a real {@code ss.cli} is
 * {@link StateSmithIntegrationTest}, gated on the tool being on PATH.
 */
class StateSmithStateMachineTest {
    @TempDir
    Path temporaryDirectory;

    private static final String DOOR_YAML = """
            kind: state-machine
            engine: statesmith
            name: door
            description: Door controller
            includes: []
            context:
              - uint32_t open_count

            initial: CLOSED

            states:
              - { name: CLOSED }
              - { name: LOCKED }
              - { name: FAULT }
              - name: OPERATING
                initial: OPENING
                states:
                  - { name: OPENING }
                  - { name: OPEN }
                  - { name: CLOSING }

            events:
              - { name: OPEN_REQUEST, parameters: [] }
              - { name: CLOSE_REQUEST }
              - { name: END_STOP }
              - { name: MOTOR_FAULT, parameters: [uint32_t code] }

            transitions:
              - { from: CLOSED, event: OPEN_REQUEST, to: OPENING, guard: true }
              - { from: OPERATING, event: MOTOR_FAULT, to: FAULT }
              - { from: OPENING, event: END_STOP, to: OPEN }
              - { from: OPEN, event: tick, to: CLOSING, guard: true }
            """;

    // ------------------------------------------------------------------------------------------
    // Renderer-level: plantuml, hooks, api - no PinfitGenerator, no ss.cli.
    // ------------------------------------------------------------------------------------------

    @Test
    void rendersDeterministicPlantUmlHooksAndApi() throws Exception {
        StateMachineSpec machine = loadDoorSpec();
        ProjectConfig project = defaultProject();

        String plantuml1 = renderPlantUml(project, machine);
        String plantuml2 = renderPlantUml(project, machine);
        assertEquals(plantuml1, plantuml2, "plantuml rendering must be deterministic");

        assertTrue(plantuml1.contains("' /*@Pinfit(file:state-machine-plantuml:door.state-machine.yaml)*/"));
        assertTrue(plantuml1.contains("@startuml door_sm"));
        assertTrue(plantuml1.contains("state OPERATING {"));
        assertTrue(plantuml1.contains("[*] --> OPENING"));
        assertTrue(plantuml1.contains("[*] --> CLOSED"));
        assertTrue(plantuml1.contains(
                "CLOSED --> OPENING : OPEN_REQUEST [door_hook_transition_CLOSED_OPEN_REQUEST_guard(sm->vars.context)] / door_hook_transition_CLOSED_OPEN_REQUEST_action(sm->vars.context);"));
        assertTrue(plantuml1.contains(
                "OPEN --> CLOSING : do [door_hook_transition_OPEN_tick_guard(sm->vars.context)] / door_hook_transition_OPEN_tick_action(sm->vars.context);"));
        assertTrue(plantuml1.contains("OPERATING : enter / door_hook_state_OPERATING_entry(sm->vars.context);"));
        assertTrue(plantuml1.contains("VariableDeclarations = \"\"\"\ndoor_context_t *context;\n\"\"\""));
        assertTrue(plantuml1.contains("CFileIncludes = \"\"\"\n#include \"../door_hooks.h\"\n\"\"\""));
        assertTrue(plantuml1.contains("HFileTop = \"\"\"\ntypedef struct door_context_t door_context_t;\n\"\"\""));

        String hooksHeader = invokeRender("StateSmithHooksRenderer", "renderHeader", project, machine, emptyRegions());
        assertTrue(hooksHeader.contains("void door_hook_state_OPENING_entry(door_context_t *context);"));
        assertTrue(hooksHeader.contains("bool door_hook_transition_CLOSED_OPEN_REQUEST_guard(door_context_t *context);"));
        assertTrue(hooksHeader.contains("void door_hook_transition_OPERATING_MOTOR_FAULT_action(door_context_t *context);"));
        assertTrue(hooksHeader.contains("#include \"door.h\""));

        String apiHeader = invokeRender("StateSmithApiRenderer", "renderHeader", project, machine, emptyRegions());
        assertTrue(apiHeader.contains("DOOR_STATE_CLOSED"));
        assertTrue(apiHeader.contains("DOOR_STATE_LOCKED"));
        assertTrue(apiHeader.contains("DOOR_STATE_FAULT"));
        assertTrue(apiHeader.contains("DOOR_STATE_OPENING"));
        assertTrue(apiHeader.contains("DOOR_STATE_OPEN"));
        assertTrue(apiHeader.contains("DOOR_STATE_CLOSING"));
        assertTrue(!apiHeader.contains("DOOR_STATE_OPERATING"), "composite states must not appear in door_state_t");
        assertTrue(apiHeader.contains("door_sm sm;"));
        assertTrue(apiHeader.contains("door_event_args_MOTOR_FAULT_t MOTOR_FAULT;"));
        assertTrue(apiHeader.contains("uint32_t code;"));
        assertTrue(!apiHeader.contains("door_go_to_state"), "statesmith engine must not generate go_to_state");
        assertTrue(apiHeader.contains("#include \"door_sm/door_sm.h\""));

        String apiSource = invokeRender("StateSmithApiRenderer", "renderSource", project, machine, emptyRegions());
        assertTrue(apiSource.contains("door_sm_ctor(&context->sm);"));
        assertTrue(apiSource.contains("door_sm_start(&context->sm);"));
        assertTrue(apiSource.contains("door_sm_dispatch_event(&context->sm, door_sm_EventId_DO);"));
        assertTrue(apiSource.contains("context->event_args.MOTOR_FAULT.code = code;"));
        assertTrue(apiSource.contains("door_sm_dispatch_event(&context->sm, door_sm_EventId_MOTOR_FAULT);"));
    }

    @Test
    void preservesUserRegionsAcrossRegeneration() throws Exception {
        StateMachineSpec machine = loadDoorSpec();
        ProjectConfig project = defaultProject();
        TagHelper tags = new TagHelper();

        String firstHooksSource = invokeRender("StateSmithHooksRenderer", "renderSource", project, machine, emptyRegions());
        Path hooksFile = temporaryDirectory.resolve("door_hooks.c");
        tags.writeGenerated(hooksFile, firstHooksSource, "\n");

        String edited = Files.readString(hooksFile).replace(
                "    /*@Pinfit usercode+ state.CLOSED.entry*/\n    /*@Pinfit usercode-*/",
                "    /*@Pinfit usercode+ state.CLOSED.entry*/\n    context->open_count = 0U;\n    /*@Pinfit usercode-*/");
        Files.writeString(hooksFile, edited);

        UserRegions carried = tags.readForGeneration(hooksFile, false);
        String secondHooksSource = invokeRender("StateSmithHooksRenderer", "renderSource", project, machine, carried);
        assertTrue(secondHooksSource.contains("context->open_count = 0U;"));
    }

    @Test
    void switchingEngineFromBuiltinKeepsUserCodeInTheHooksFile() throws Exception {
        String builtinYaml = """
                kind: state-machine
                name: door
                description: Door controller
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - { name: OPEN }
                events:
                  - { name: OPEN_REQUEST, parameters: [] }
                transitions:
                  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN, guard: false }
                """;
        Path yamlPath = temporaryDirectory.resolve("door.state-machine.yaml");
        Files.writeString(yamlPath, builtinYaml);
        StateMachineSpec builtinMachine = StateMachineSpec.from(yamlPath, new YamlFiles().load(yamlPath));
        ProjectConfig project = defaultProject();

        Class<?> builtinRendererClass = Class.forName("com.daoekinc.pinfit.generate.StateMachineRenderer");
        Constructor<?> builtinConstructor = builtinRendererClass.getDeclaredConstructor();
        builtinConstructor.setAccessible(true);
        Object builtinRenderer = builtinConstructor.newInstance();
        Method renderSource = builtinRendererClass.getDeclaredMethod("renderSource", ProjectConfig.class, StateMachineSpec.class,
                Class.forName("com.daoekinc.pinfit.generate.DocumentationRenderer"), UserRegions.class);
        renderSource.setAccessible(true);
        Object docs = documentationRenderer(project);
        String builtinSource = (String) renderSource.invoke(builtinRenderer, project, builtinMachine, docs, emptyRegions());

        TagHelper tags = new TagHelper();
        Path builtinSourceFile = temporaryDirectory.resolve("door.c");
        tags.writeGenerated(builtinSourceFile, builtinSource, "\n");
        String edited = Files.readString(builtinSourceFile).replace(
                "    /*@Pinfit usercode+ state.CLOSED.entry*/\n    /*@Pinfit usercode-*/",
                "    /*@Pinfit usercode+ state.CLOSED.entry*/\n    context->state = context->state;\n    /*@Pinfit usercode-*/");
        Files.writeString(builtinSourceFile, edited);
        UserRegions carried = tags.readForGeneration(builtinSourceFile, false);

        String statesmithYaml = """
                kind: state-machine
                engine: statesmith
                name: door
                description: Door controller
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - { name: OPEN }
                events:
                  - { name: OPEN_REQUEST, parameters: [] }
                transitions:
                  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN, guard: false }
                """;
        Files.writeString(yamlPath, statesmithYaml);
        StateMachineSpec statesmithMachine = StateMachineSpec.from(yamlPath, new YamlFiles().load(yamlPath));
        String hooksSource = invokeRender("StateSmithHooksRenderer", "renderSource", project, statesmithMachine, carried);
        assertTrue(hooksSource.contains("context->state = context->state;"),
                "region 'state.CLOSED.entry' must carry over when switching engine: builtin -> statesmith");
    }

    // ------------------------------------------------------------------------------------------
    // Validation - through the real CLI, since it fails before ss.cli would ever be invoked.
    // ------------------------------------------------------------------------------------------

    @Test
    void rejectsDuplicateStateNameAcrossHierarchy() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - name: OPERATING
                    initial: CLOSED
                    states:
                      - { name: CLOSED }
                events: []
                transitions: []
                """, "duplicate name 'CLOSED'");
    }

    @Test
    void rejectsCompositeEnteredDirectlyWithoutInitial() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - name: OPERATING
                    states:
                      - { name: OPENING }
                events:
                  - { name: GO, parameters: [] }
                transitions:
                  - { from: CLOSED, event: GO, to: OPERATING, guard: false }
                """, "must declare its own 'initial'");
    }

    @Test
    void rejectsEventLiterallyNamedTick() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                events:
                  - { name: tick, parameters: [] }
                transitions: []
                """, "reserved for the polled do-event");
    }

    @Test
    void rejectsTransitionToUnknownState() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                events:
                  - { name: GO, parameters: [] }
                transitions:
                  - { from: CLOSED, event: GO, to: NOPE, guard: false }
                """, "references unknown state 'NOPE'");
    }

    @Test
    void rejectsUnknownEngine() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: bogus
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                events: []
                transitions: []
                """, "engine must be 'builtin' or 'statesmith'");
    }

    @Test
    void rejectsEmptyExplicitStatesList() throws Exception {
        assertValidationError("""
                kind: state-machine
                engine: statesmith
                name: door
                initial: CLOSED
                states:
                  - { name: CLOSED }
                  - name: OPERATING
                    states: []
                events: []
                transitions: []
                """, "needs at least one state when present");
    }

    private void assertValidationError(String yaml, String expectedFragment) throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        Files.writeString(temporaryDirectory.resolve("door.state-machine.yaml"), yaml);
        assertEquals(1, cli.run("generate"));
        assertTrue(cli.errors().contains(expectedFragment), "expected error containing '" + expectedFragment + "', got:\n" + cli.errors());
    }

    // ------------------------------------------------------------------------------------------

    private StateMachineSpec loadDoorSpec() throws Exception {
        Path yamlPath = temporaryDirectory.resolve("door.state-machine.yaml");
        Files.writeString(yamlPath, DOOR_YAML);
        return StateMachineSpec.from(yamlPath, new YamlFiles().load(yamlPath));
    }

    private ProjectConfig defaultProject() throws Exception {
        Path projectFile = temporaryDirectory.resolve("pinfit.yaml");
        if (!Files.exists(projectFile)) {
            Files.writeString(projectFile, """
                    schema: 1
                    name: 'test'
                    version: 0.1.0
                    """);
        }
        return ProjectConfig.from(projectFile, new YamlFiles().load(projectFile));
    }

    private String renderPlantUml(ProjectConfig project, StateMachineSpec machine) throws Exception {
        Class<?> rendererClass = Class.forName("com.daoekinc.pinfit.generate.StateSmithPlantUmlRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object renderer = constructor.newInstance();
        Method method = rendererClass.getDeclaredMethod("render", ProjectConfig.class, StateMachineSpec.class);
        method.setAccessible(true);
        return (String) method.invoke(renderer, project, machine);
    }

    private UserRegions emptyRegions() {
        return new TagHelper().readForGeneration(temporaryDirectory.resolve("__no_such_file__"), false);
    }

    private Object documentationRenderer(ProjectConfig project) throws Exception {
        Class<?> docsClass = Class.forName("com.daoekinc.pinfit.generate.DocumentationRenderer");
        Constructor<?> constructor = docsClass.getDeclaredConstructor(ProjectConfig.class, YamlFiles.class);
        constructor.setAccessible(true);
        return constructor.newInstance(project, new YamlFiles());
    }

    /**
     * The statesmith renderers are package-private in {@code com.daoekinc.pinfit.generate} (an
     * internal detail, not part of Pinfit's public API) - reflection here is the test-only way to
     * exercise them directly without going through {@code PinfitGenerator.generate()}, which would
     * also invoke {@code ss.cli}.
     */
    private String invokeRender(String simpleClassName, String methodName, ProjectConfig project, StateMachineSpec machine,
                                UserRegions regions) throws Exception {
        Class<?> rendererClass = Class.forName("com.daoekinc.pinfit.generate." + simpleClassName);
        Constructor<?> rendererConstructor = rendererClass.getDeclaredConstructor();
        rendererConstructor.setAccessible(true);
        Object renderer = rendererConstructor.newInstance();
        Class<?> docsClass = Class.forName("com.daoekinc.pinfit.generate.DocumentationRenderer");
        Method method = rendererClass.getDeclaredMethod(methodName, ProjectConfig.class, StateMachineSpec.class, docsClass, UserRegions.class);
        method.setAccessible(true);
        return (String) method.invoke(renderer, project, machine, documentationRenderer(project), regions);
    }
}
