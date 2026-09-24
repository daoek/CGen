package com.daoekinc.pinfit.generate;

import static com.daoekinc.pinfit.generate.RenderSupport.appendIncludes;
import static com.daoekinc.pinfit.generate.RenderSupport.appendParameters;
import static com.daoekinc.pinfit.generate.RenderSupport.appendTypedName;
import static com.daoekinc.pinfit.generate.RenderSupport.functionName;
import static com.daoekinc.pinfit.generate.RenderSupport.indent;
import static com.daoekinc.pinfit.generate.RenderSupport.macro;

import com.daoekinc.pinfit.model.InterfaceSpec;
import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.model.StateMachineSpec;
import com.daoekinc.pinfit.model.StateMachineSpec.Event;
import com.daoekinc.pinfit.model.StateMachineSpec.State;
import com.daoekinc.pinfit.tag.PinfitTag;
import com.daoekinc.pinfit.tag.TagHelper.UserRegions;
import java.util.List;

/**
 * Emits {@code <name>.h/.c} - the Pinfit-owned public API for an {@code engine: statesmith}
 * machine, kept to the same shape as the builtin engine's (see {@link StateMachineRenderer})
 * minus {@code go_to_state}: {@code <name>_state_t} is leaf states only, so callers don't need to
 * care which engine generated the machine. Conditional moves that builtin expresses with
 * {@code go_to_state} are expressed here as an {@code event: tick} transition with a guard.
 */
final class StateSmithApiRenderer {
    String renderHeader(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        String guard = macro(machine.header());
        StringBuilder out = new StringBuilder();
        out.append(PinfitTag.generatedFile("state-machine-statesmith-api-header", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(machine.header(), machine.description())).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        List<String> smInclude = List.of("\"" + StateSmithNaming.smDirectoryName(machine.name()) + "/"
                + StateSmithNaming.smHeaderFileName(machine.name()) + "\"");
        appendIncludes(out, machine.includes(), smInclude);
        out.append(user.render("state-machine.header.preamble", "")).append('\n');

        appendStateEnum(out, project, machine, docs);
        appendEventArgStructs(out, project, machine, docs);
        appendContextStruct(out, project, machine);
        appendCoreDeclarations(out, project, machine);
        appendEventDeclarations(out, project, machine, docs);

        out.append(user.render("state-machine.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    private static void appendStateEnum(StringBuilder out, ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs) {
        List<State> leaves = machine.leafStates();
        out.append(PinfitTag.generatedItem("state-enum", machine.name())).append('\n');
        out.append(docs.type(machine.name() + "_state_t", "States of " + machine.name()));
        out.append("typedef enum\n{\n");
        for (int index = 0; index < leaves.size(); index++) {
            out.append(indent(project, 1)).append(stateConstant(machine, leaves.get(index).name()));
            if (index + 1 < leaves.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("} ").append(machine.name()).append("_state_t;\n\n");
    }

    private static void appendEventArgStructs(StringBuilder out, ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs) {
        for (Event event : machine.events()) {
            if (event.parameters().isEmpty()) {
                continue;
            }
            String type = eventArgsType(machine, event);
            out.append(PinfitTag.generatedItem("struct", type)).append('\n');
            out.append(docs.type(type, "Stored parameters for " + event.name() + ", read by its hooks"));
            out.append("typedef struct\n{\n");
            for (InterfaceSpec.Parameter parameter : event.parameters()) {
                out.append(indent(project, 1));
                appendTypedName(out, parameter.type(), parameter.name());
                out.append(";\n");
            }
            out.append("} ").append(type).append(";\n\n");
        }
        if (hasEventArgs(machine)) {
            String aggregateType = eventArgsAggregateType(machine);
            out.append(PinfitTag.generatedItem("struct", aggregateType)).append('\n');
            out.append(docs.type(aggregateType, "Stored parameters for every event of " + machine.name() + " that has any"));
            out.append("typedef struct\n{\n");
            for (Event event : machine.events()) {
                if (event.parameters().isEmpty()) {
                    continue;
                }
                out.append(indent(project, 1)).append(eventArgsType(machine, event)).append(' ').append(event.name()).append(";\n");
            }
            out.append("} ").append(aggregateType).append(";\n\n");
        }
    }

    private static void appendContextStruct(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        String smType = StateSmithNaming.smIdentifier(machine.name());
        out.append(PinfitTag.generatedItem("context", machine.name())).append('\n');
        // Tagged, not a typedef: <name>_sm.h already forward-declares
        // "typedef struct <name>_context_t <name>_context_t;" (StateSmith's vars.context needs it),
        // and C99 forbids repeating that typedef - an anonymous struct here would be a second,
        // conflicting type.
        out.append("struct ").append(machine.name()).append("_context_t\n{\n");
        for (InterfaceSpec.Field field : machine.context()) {
            out.append(indent(project, 1));
            appendTypedName(out, field.type(), field.name());
            out.append(";\n");
        }
        out.append(indent(project, 1)).append(smType).append(" sm;\n");
        if (hasEventArgs(machine)) {
            out.append(indent(project, 1)).append(eventArgsAggregateType(machine)).append(" event_args;\n");
        }
        out.append("};\n\n");
    }

    private static void appendCoreDeclarations(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        String init = functionName(project, machine.name(), "init");
        out.append(PinfitTag.generatedItem("function", init)).append('\n');
        out.append("void ").append(init).append('(').append(machine.name()).append("_context_t *context);\n\n");

        String tick = functionName(project, machine.name(), "tick");
        out.append(PinfitTag.generatedItem("function", tick)).append('\n');
        out.append("void ").append(tick).append('(').append(machine.name()).append("_context_t *context);\n\n");

        String getState = functionName(project, machine.name(), "get_state");
        out.append(PinfitTag.generatedItem("function", getState)).append('\n');
        out.append(machine.name()).append("_state_t ").append(getState).append("(const ").append(machine.name()).append("_context_t *context);\n\n");
    }

    private static void appendEventDeclarations(StringBuilder out, ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs) {
        for (Event event : machine.events()) {
            String onEvent = functionName(project, machine.name(), "on", event.name());
            out.append(PinfitTag.generatedItem("function", onEvent)).append('\n');
            out.append(docs.function(onEvent, event.description(), "void", event.parameters()));
            out.append("void ").append(onEvent).append('(').append(machine.name()).append("_context_t *context");
            appendParameters(out, event.parameters(), true);
            out.append(");\n\n");
        }
    }

    String renderSource(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(PinfitTag.generatedFile("state-machine-statesmith-api-source", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(machine.sourceFile(), machine.description())).append('\n');
        out.append("#include \"").append(machine.header()).append("\"\n");
        out.append("#include <stddef.h>\n\n");
        out.append(user.render("state-machine.source.includes", "")).append('\n');

        appendInitFunction(out, project, machine);
        appendTickFunction(out, project, machine);
        appendGetStateFunction(out, project, machine);
        appendEventFunctions(out, project, machine);

        out.append(user.render("state-machine.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }

    private static void appendInitFunction(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        String init = functionName(project, machine.name(), "init");
        out.append(PinfitTag.generatedItem("function", init)).append('\n');
        out.append("void ").append(init).append('(').append(machine.name()).append("_context_t *context)\n{\n");
        out.append(indent(project, 1)).append("if (context != NULL)\n").append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append("context->sm.vars.context = context;\n");
        out.append(indent(project, 2)).append(StateSmithNaming.smCtor(machine.name())).append("(&context->sm);\n");
        out.append(indent(project, 2)).append(StateSmithNaming.smStart(machine.name())).append("(&context->sm);\n");
        out.append(indent(project, 1)).append("}\n");
        out.append("}\n\n");
    }

    private static void appendTickFunction(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        String tick = functionName(project, machine.name(), "tick");
        out.append(PinfitTag.generatedItem("function", tick)).append('\n');
        out.append("void ").append(tick).append('(').append(machine.name()).append("_context_t *context)\n{\n");
        out.append(indent(project, 1)).append("if (context != NULL)\n").append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append(StateSmithNaming.smDispatchEvent(machine.name()))
                .append("(&context->sm, ").append(StateSmithNaming.smIdentifier(machine.name())).append("_EventId_DO);\n");
        out.append(indent(project, 1)).append("}\n");
        out.append("}\n\n");
    }

    private static void appendGetStateFunction(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        String getState = functionName(project, machine.name(), "get_state");
        String fallback = stateConstant(machine, machine.effectiveInitialLeaf().name());
        out.append(PinfitTag.generatedItem("function", getState)).append('\n');
        out.append(machine.name()).append("_state_t ").append(getState).append("(const ").append(machine.name()).append("_context_t *context)\n{\n");
        out.append(indent(project, 1)).append(machine.name()).append("_state_t pinfit_result = ").append(fallback).append(";\n\n");
        out.append(indent(project, 1)).append("if (context != NULL)\n").append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append("switch (context->sm.state_id)\n").append(indent(project, 2)).append("{\n");
        for (State leaf : machine.leafStates()) {
            out.append(indent(project, 3)).append("case ").append(StateSmithNaming.smStateIdValue(machine.name(), leaf.name())).append(":\n");
            out.append(indent(project, 4)).append("pinfit_result = ").append(stateConstant(machine, leaf.name())).append(";\n");
            out.append(indent(project, 4)).append("break;\n\n");
        }
        out.append(indent(project, 3)).append("default:\n").append(indent(project, 4)).append("break;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n\n");
        out.append(indent(project, 1)).append("return pinfit_result;\n");
        out.append("}\n\n");
    }

    private static void appendEventFunctions(StringBuilder out, ProjectConfig project, StateMachineSpec machine) {
        for (Event event : machine.events()) {
            String onEvent = functionName(project, machine.name(), "on", event.name());
            out.append(PinfitTag.generatedItem("function", onEvent)).append('\n');
            out.append("void ").append(onEvent).append('(').append(machine.name()).append("_context_t *context");
            appendParameters(out, event.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append("if (context != NULL)\n").append(indent(project, 1)).append("{\n");
            for (InterfaceSpec.Parameter parameter : event.parameters()) {
                out.append(indent(project, 2)).append("context->event_args.").append(event.name()).append('.')
                        .append(parameter.name()).append(" = ").append(parameter.name()).append(";\n");
            }
            out.append(indent(project, 2)).append(StateSmithNaming.smDispatchEvent(machine.name()))
                    .append("(&context->sm, ").append(StateSmithNaming.smEventIdValue(machine.name(), event.name())).append(");\n");
            out.append(indent(project, 1)).append("}\n");
            out.append("}\n\n");
        }
    }

    private static boolean hasEventArgs(StateMachineSpec machine) {
        return machine.events().stream().anyMatch(event -> !event.parameters().isEmpty());
    }

    private static String eventArgsType(StateMachineSpec machine, Event event) {
        return machine.name() + "_event_args_" + event.name() + "_t";
    }

    private static String eventArgsAggregateType(StateMachineSpec machine) {
        return machine.name() + "_event_args_t";
    }

    private static String stateConstant(StateMachineSpec machine, String stateName) {
        return (machine.name() + "_state_" + stateName).toUpperCase();
    }
}
