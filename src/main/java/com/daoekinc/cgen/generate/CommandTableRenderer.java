package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;

import com.daoekinc.cgen.model.CommandTableSpec;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

final class CommandTableRenderer {
    String renderHeader(ProjectConfig project, CommandTableSpec table, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("command-table-header", table.source().getFileName().toString())).append('\n');
        out.append(docs.file(table.header(), table.description())).append('\n');
        String guard = macro(table.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        appendIncludes(out, List.of("<stdint.h>"), table.includes());
        out.append(user.render("command-table.header.preamble", "")).append('\n');

        out.append(CGenTag.generatedItem("command-enum", table.name())).append('\n');
        out.append(docs.type(table.name() + "_command_t", "Commands of " + table.name()));
        out.append("typedef enum\n{\n");
        for (int index = 0; index < table.commands().size(); index++) {
            CommandTableSpec.Command command = table.commands().get(index);
            out.append(indent(project, 1)).append(constant(table, command.name())).append(" = ").append(command.opcode());
            if (index + 1 < table.commands().size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("} ").append(table.name()).append("_command_t;\n\n");

        out.append(CGenTag.generatedItem("context", table.name())).append('\n');
        out.append("typedef struct\n{\n");
        if (table.context().isEmpty()) {
            out.append(indent(project, 1)).append("unsigned char reserved;\n");
        } else {
            for (InterfaceSpec.Field field : table.context()) {
                out.append(indent(project, 1));
                appendTypedName(out, field.type(), field.name());
                out.append(";\n");
            }
        }
        out.append("} ").append(table.name()).append("_context_t;\n\n");

        out.append(CGenTag.generatedItem("function", "dispatch")).append('\n');
        out.append("void ").append(table.name()).append("_dispatch(").append(table.name()).append("_context_t *context, ")
                .append(table.name()).append("_command_t command, const uint8_t *payload, uint32_t length);\n\n");

        out.append(user.render("command-table.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    String renderSource(ProjectConfig project, CommandTableSpec table, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("command-table-source", table.source().getFileName().toString())).append('\n');
        out.append(docs.file(table.sourceFile(), table.description())).append('\n');
        out.append("#include \"").append(table.header()).append("\"\n\n");
        out.append(user.render("command-table.source.includes", "")).append('\n');

        for (CommandTableSpec.Command command : table.commands()) {
            out.append(CGenTag.generatedItem("private-function", table.name() + "_handle_" + command.name())).append('\n');
            out.append("static void ").append(table.name()).append("_handle_").append(command.name())
                    .append('(').append(table.name()).append("_context_t *context, const uint8_t *payload, uint32_t length)\n{\n")
                    .append(indent(project, 1)).append("(void)context;\n")
                    .append(indent(project, 1)).append("(void)payload;\n")
                    .append(indent(project, 1)).append("(void)length;\n\n");
            out.append(user.render("command." + command.name() + ".body", ""));
            out.append("}\n\n");
        }

        out.append(CGenTag.generatedItem("function", "dispatch")).append('\n');
        out.append("void ").append(table.name()).append("_dispatch(").append(table.name()).append("_context_t *context, ")
                .append(table.name()).append("_command_t command, const uint8_t *payload, uint32_t length)\n{\n");
        out.append(indent(project, 1)).append("switch (command)\n").append(indent(project, 1)).append("{\n");
        for (CommandTableSpec.Command command : table.commands()) {
            out.append(indent(project, 2)).append("case ").append(constant(table, command.name())).append(":\n");
            out.append(indent(project, 3)).append(table.name()).append("_handle_").append(command.name())
                    .append("(context, payload, length);\n");
            out.append(indent(project, 3)).append("break;\n\n");
        }
        out.append(indent(project, 2)).append("default:\n").append(indent(project, 2)).append("{\n");
        out.append(user.render("command.unknown", ""));
        out.append(indent(project, 3)).append("break;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n");
        out.append("}\n\n");

        out.append(user.render("command-table.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }

    private static String constant(CommandTableSpec table, String commandName) {
        return (table.name() + "_cmd_" + commandName).toUpperCase();
    }
}
