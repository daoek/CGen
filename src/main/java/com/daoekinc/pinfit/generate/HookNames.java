package com.daoekinc.pinfit.generate;

import static com.daoekinc.pinfit.generate.RenderSupport.functionName;

import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.model.StateMachineSpec;
import com.daoekinc.pinfit.model.StateMachineSpec.Transition;

/**
 * Hook function names shared by {@link StateSmithPlantUmlRenderer} (which calls them from the
 * emitted PlantUML) and {@link StateSmithHooksRenderer} (which defines them) - one place so the
 * two can never drift apart. Region names (what the user actually edits) are the plain
 * {@code state.<STATE>.entry} etc. strings, identical to the builtin engine's - see
 * {@link StateSmithHooksRenderer}.
 */
final class HookNames {
    private HookNames() {
    }

    static String stateEntry(ProjectConfig project, StateMachineSpec machine, String stateName) {
        return functionName(project, machine.name(), "hook", "state", stateName, "entry");
    }

    static String stateExit(ProjectConfig project, StateMachineSpec machine, String stateName) {
        return functionName(project, machine.name(), "hook", "state", stateName, "exit");
    }

    static String stateTick(ProjectConfig project, StateMachineSpec machine, String stateName) {
        return functionName(project, machine.name(), "hook", "state", stateName, "tick");
    }

    static String transitionGuard(ProjectConfig project, StateMachineSpec machine, Transition transition) {
        return functionName(project, machine.name(), "hook", "transition", transition.from(), transition.event(), "guard");
    }

    static String transitionAction(ProjectConfig project, StateMachineSpec machine, Transition transition) {
        return functionName(project, machine.name(), "hook", "transition", transition.from(), transition.event(), "action");
    }
}
