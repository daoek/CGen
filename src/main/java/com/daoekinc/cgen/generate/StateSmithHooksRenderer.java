package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendUnusedSilencer;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;

import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.model.StateMachineSpec;
import com.daoekinc.cgen.model.StateMachineSpec.State;
import com.daoekinc.cgen.model.StateMachineSpec.Transition;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

/**
 * Emits {@code <name>_hooks.h/.c} - entirely CGen-owned, every function body a usercode region.
 * Region names are identical to the builtin engine's ({@code state.<STATE>.entry/exit/tick},
 * {@code transition.<from>.<event>.guard/action}), so switching {@code engine:} keeps the user's
 * code. Every function is non-{@code static}: StateSmith's generated {@code <name>_sm.c} calls
 * them from a separate translation unit.
 */
final class StateSmithHooksRenderer {
    String renderHeader(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        String hooksHeaderName = StateSmithNaming.hooksHeaderFileName(machine.name());
        String guard = macro(hooksHeaderName);
        String contextType = machine.name() + "_context_t";
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("state-machine-hooks-header", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(hooksHeaderName, machine.description() + " - StateSmith hooks")).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        out.append("#include \"").append(machine.name()).append(".h\"\n\n");
        out.append(user.render("state-machine.hooks-header.preamble", "")).append('\n');

        for (State state : machine.allStatesFlat()) {
            appendStateDeclarations(out, project, machine, docs, contextType, state.name());
        }
        for (Transition transition : machine.transitions()) {
            appendTransitionDeclarations(out, project, machine, docs, contextType, transition);
        }

        out.append(user.render("state-machine.hooks-header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    private static void appendStateDeclarations(StringBuilder out, ProjectConfig project, StateMachineSpec machine,
                                                 DocumentationRenderer docs, String contextType, String stateName) {
        String entry = HookNames.stateEntry(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", entry)).append('\n');
        out.append(docs.function(entry, "Entry hook for " + stateName, "void", List.of()));
        out.append("void ").append(entry).append('(').append(contextType).append(" *context);\n\n");

        String exit = HookNames.stateExit(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", exit)).append('\n');
        out.append(docs.function(exit, "Exit hook for " + stateName, "void", List.of()));
        out.append("void ").append(exit).append('(').append(contextType).append(" *context);\n\n");

        String tick = HookNames.stateTick(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", tick)).append('\n');
        out.append(docs.function(tick, "Tick (do-event) hook for " + stateName, "void", List.of()));
        out.append("void ").append(tick).append('(').append(contextType).append(" *context);\n\n");
    }

    private static void appendTransitionDeclarations(StringBuilder out, ProjectConfig project, StateMachineSpec machine,
                                                      DocumentationRenderer docs, String contextType, Transition transition) {
        if (transition.guard()) {
            String guard = HookNames.transitionGuard(project, machine, transition);
            out.append(CGenTag.generatedItem("function", guard)).append('\n');
            out.append(docs.function(guard, "Guard for " + transition.from() + " -> " + transition.to()
                    + " on " + transition.event(), "bool", List.of()));
            out.append("bool ").append(guard).append('(').append(contextType).append(" *context);\n\n");
        }
        String action = HookNames.transitionAction(project, machine, transition);
        out.append(CGenTag.generatedItem("function", action)).append('\n');
        out.append(docs.function(action, "Action for " + transition.from() + " -> " + transition.to()
                + " on " + transition.event(), "void", List.of()));
        out.append("void ").append(action).append('(').append(contextType).append(" *context);\n\n");
    }

    String renderSource(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        String hooksHeaderName = StateSmithNaming.hooksHeaderFileName(machine.name());
        String contextType = machine.name() + "_context_t";
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("state-machine-hooks-source", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(StateSmithNaming.hooksSourceFileName(machine.name()), machine.description() + " - StateSmith hooks")).append('\n');
        out.append("#include \"").append(hooksHeaderName).append("\"\n");
        out.append("#include <stdbool.h>\n\n");
        out.append(user.render("state-machine.hooks-source.includes", "")).append('\n');

        for (State state : machine.allStatesFlat()) {
            appendStateDefinitions(out, project, machine, contextType, user, state.name());
        }
        for (Transition transition : machine.transitions()) {
            appendTransitionDefinitions(out, project, machine, contextType, user, transition);
        }

        out.append(user.render("state-machine.hooks-source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }

    private static void appendStateDefinitions(StringBuilder out, ProjectConfig project, StateMachineSpec machine,
                                               String contextType, UserRegions user, String stateName) {
        String entry = HookNames.stateEntry(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", entry)).append('\n');
        out.append("void ").append(entry).append('(').append(contextType).append(" *context)\n{\n");
        appendUnusedSilencer(out, project, 1, "context");
        out.append('\n').append(user.render("state." + stateName + ".entry", "", indent(project, 1)));
        out.append("}\n\n");

        String exit = HookNames.stateExit(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", exit)).append('\n');
        out.append("void ").append(exit).append('(').append(contextType).append(" *context)\n{\n");
        appendUnusedSilencer(out, project, 1, "context");
        out.append('\n').append(user.render("state." + stateName + ".exit", "", indent(project, 1)));
        out.append("}\n\n");

        String tick = HookNames.stateTick(project, machine, stateName);
        out.append(CGenTag.generatedItem("function", tick)).append('\n');
        out.append("void ").append(tick).append('(').append(contextType).append(" *context)\n{\n");
        appendUnusedSilencer(out, project, 1, "context");
        out.append('\n').append(user.render("state." + stateName + ".tick", "", indent(project, 1)));
        out.append("}\n\n");
    }

    private static void appendTransitionDefinitions(StringBuilder out, ProjectConfig project, StateMachineSpec machine,
                                                     String contextType, UserRegions user, Transition transition) {
        String regionKey = transition.from() + "." + transition.event();
        if (transition.guard()) {
            String guard = HookNames.transitionGuard(project, machine, transition);
            out.append(CGenTag.generatedItem("function", guard)).append('\n');
            out.append("bool ").append(guard).append('(').append(contextType).append(" *context)\n{\n");
            appendUnusedSilencer(out, project, 1, "context");
            out.append(indent(project, 1)).append("bool cgen_guard = true;\n\n");
            out.append(user.render("transition." + regionKey + ".guard", "", indent(project, 1)));
            out.append('\n').append(indent(project, 1)).append("return cgen_guard;\n");
            out.append("}\n\n");
        }
        String action = HookNames.transitionAction(project, machine, transition);
        out.append(CGenTag.generatedItem("function", action)).append('\n');
        out.append("void ").append(action).append('(').append(contextType).append(" *context)\n{\n");
        appendUnusedSilencer(out, project, 1, "context");
        out.append('\n').append(user.render("transition." + regionKey + ".action", "", indent(project, 1)));
        out.append("}\n\n");
    }
}
