package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;

import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.model.StateMachineSpec;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

final class StateMachineRenderer {
    String renderHeader(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("state-machine-header", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(machine.header(), machine.description())).append('\n');
        String guard = macro(machine.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        appendIncludes(out, machine.includes());
        out.append(user.render("state-machine.header.preamble", "")).append('\n');

        out.append(CGenTag.generatedItem("state-enum", machine.name())).append('\n');
        out.append(docs.type(machine.name() + "_state_t", "States of " + machine.name()));
        out.append("typedef enum\n{\n");
        for (int index = 0; index < machine.states().size(); index++) {
            out.append(indent(project, 1)).append(stateConstant(machine, machine.states().get(index).name()));
            if (index + 1 < machine.states().size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("} ").append(machine.name()).append("_state_t;\n\n");

        out.append(CGenTag.generatedItem("context", machine.name())).append('\n');
        out.append("typedef struct\n{\n").append(indent(project, 1)).append(machine.name()).append("_state_t state;\n");
        for (InterfaceSpec.Field field : machine.context()) {
            out.append(indent(project, 1));
            appendTypedName(out, field.type(), field.name());
            out.append(";\n");
        }
        out.append("} ").append(machine.name()).append("_context_t;\n\n");

        out.append(CGenTag.generatedItem("function", "init")).append('\n');
        out.append("void ").append(machine.name()).append("_init(").append(machine.name()).append("_context_t *context);\n\n");

        for (StateMachineSpec.Event event : machine.events()) {
            out.append(CGenTag.generatedItem("function", "on_" + event.name())).append('\n');
            out.append(docs.function(machine.name() + "_on_" + event.name(), event.description(), "void", event.parameters()));
            out.append("void ").append(machine.name()).append("_on_").append(event.name())
                    .append("(").append(machine.name()).append("_context_t *context");
            appendParameters(out, event.parameters(), true);
            out.append(");\n\n");
        }
        out.append(user.render("state-machine.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    String renderSource(ProjectConfig project, StateMachineSpec machine, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("state-machine-source", machine.source().getFileName().toString())).append('\n');
        out.append(docs.file(machine.sourceFile(), machine.description())).append('\n');
        out.append("#include \"").append(machine.header()).append("\"\n");
        out.append("#include <stdbool.h>\n\n");
        out.append(user.render("state-machine.source.includes", "")).append('\n');

        for (StateMachineSpec.State state : machine.states()) {
            out.append(CGenTag.generatedItem("private-function", machine.name() + "_enter_" + state.name())).append('\n');
            out.append("static void ").append(machine.name()).append("_enter_").append(state.name())
                    .append('(').append(machine.name()).append("_context_t *context)\n{\n")
                    .append(indent(project, 1)).append("(void)context;\n\n");
            out.append(user.render("state." + state.name() + ".entry", ""));
            out.append("}\n\n");

            out.append(CGenTag.generatedItem("private-function", machine.name() + "_exit_" + state.name())).append('\n');
            out.append("static void ").append(machine.name()).append("_exit_").append(state.name())
                    .append('(').append(machine.name()).append("_context_t *context)\n{\n")
                    .append(indent(project, 1)).append("(void)context;\n\n");
            out.append(user.render("state." + state.name() + ".exit", ""));
            out.append("}\n\n");
        }

        out.append(CGenTag.generatedItem("function", "init")).append('\n');
        out.append("void ").append(machine.name()).append("_init(").append(machine.name()).append("_context_t *context)\n{\n")
                .append(indent(project, 1)).append("context->state = ").append(stateConstant(machine, machine.initial())).append(";\n")
                .append(indent(project, 1)).append(machine.name()).append("_enter_").append(machine.initial()).append("(context);\n")
                .append("}\n\n");

        for (StateMachineSpec.Event event : machine.events()) {
            List<StateMachineSpec.Transition> handled = machine.transitions().stream()
                    .filter(transition -> transition.event().equals(event.name())).toList();

            out.append(CGenTag.generatedItem("function", "on_" + event.name())).append('\n');
            out.append("void ").append(machine.name()).append("_on_").append(event.name())
                    .append("(").append(machine.name()).append("_context_t *context");
            appendParameters(out, event.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append("bool cgen_transitioned = false;\n");
            for (InterfaceSpec.Parameter parameter : event.parameters()) {
                out.append(indent(project, 1)).append("(void)").append(parameter.name()).append(";\n");
            }
            out.append('\n');
            out.append(indent(project, 1)).append("switch (context->state)\n").append(indent(project, 1)).append("{\n");
            for (StateMachineSpec.Transition transition : handled) {
                appendTransitionCase(out, project, machine, user, transition);
            }
            out.append(indent(project, 2)).append("default:\n").append(indent(project, 3)).append("break;\n");
            out.append(indent(project, 1)).append("}\n\n");
            out.append(indent(project, 1)).append("if (!cgen_transitioned)\n").append(indent(project, 1)).append("{\n");
            out.append(user.render("event." + event.name() + ".unhandled", ""));
            out.append(indent(project, 1)).append("}\n");
            out.append("}\n\n");
        }
        out.append(user.render("state-machine.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }

    private static void appendTransitionCase(StringBuilder out, ProjectConfig project, StateMachineSpec machine,
                                              UserRegions user, StateMachineSpec.Transition transition) {
        String regionKey = transition.from() + "." + transition.event();
        out.append(indent(project, 2)).append("case ").append(stateConstant(machine, transition.from())).append(":\n");
        out.append(indent(project, 2)).append("{\n");
        int bodyLevel = 3;
        if (transition.guard()) {
            out.append(indent(project, 3)).append("bool cgen_guard = true;\n\n");
            out.append(user.render("transition." + regionKey + ".guard", ""));
            out.append(indent(project, 3)).append("if (cgen_guard)\n").append(indent(project, 3)).append("{\n");
            bodyLevel = 4;
        }
        out.append(indent(project, bodyLevel)).append(machine.name()).append("_exit_").append(transition.from()).append("(context);\n");
        out.append(user.render("transition." + regionKey + ".action", ""));
        out.append(indent(project, bodyLevel)).append("context->state = ").append(stateConstant(machine, transition.to())).append(";\n");
        out.append(indent(project, bodyLevel)).append(machine.name()).append("_enter_").append(transition.to()).append("(context);\n");
        out.append(indent(project, bodyLevel)).append("cgen_transitioned = true;\n");
        if (transition.guard()) {
            out.append(indent(project, 3)).append("}\n");
        }
        out.append(indent(project, 3)).append("break;\n");
        out.append(indent(project, 2)).append("}\n");
    }

    private static String stateConstant(StateMachineSpec machine, String stateName) {
        return (machine.name() + "_state_" + stateName).toUpperCase();
    }
}
