package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;
import static com.daoekinc.cgen.generate.RenderSupport.quotedRelative;

import com.daoekinc.cgen.model.AdapterSpec;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class AdapterRenderer {
    String renderHeader(ProjectConfig project, AdapterSpec adapter, InterfaceSpec from, InterfaceSpec to,
                        DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("adapter-header", adapter.source().getFileName().toString())).append('\n');
        out.append(docs.file(adapter.header(), adapter.description())).append('\n');
        String guard = macro(adapter.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        Path directory = adapter.source().getParent();
        List<String> contractIncludes = List.of(
                quotedRelative(directory, from.source().getParent().resolve(from.header())),
                quotedRelative(directory, to.source().getParent().resolve(to.header())));
        appendIncludes(out, contractIncludes, adapter.includes());
        out.append(user.render("adapter.header.preamble", "")).append('\n');

        out.append(CGenTag.generatedItem("context", adapter.name())).append('\n');
        out.append("typedef struct\n{\n");
        out.append(indent(project, 1)).append("const ").append(to.name()).append("_interface_t *target;\n");
        for (InterfaceSpec.Field field : adapter.context()) {
            out.append(indent(project, 1));
            appendTypedName(out, field.type(), field.name());
            out.append(";\n");
        }
        out.append("} ").append(adapter.name()).append("_context_t;\n\n");

        out.append(CGenTag.generatedItem("function", "set_target")).append('\n');
        out.append("void ").append(adapter.name()).append("_set_target(").append(adapter.name())
                .append("_context_t *context, const ").append(to.name()).append("_interface_t *target);\n\n");

        out.append(CGenTag.generatedItem("bind-function", adapter.name() + "_bind_" + from.name())).append('\n');
        out.append("void ").append(adapter.name()).append("_bind_").append(from.name()).append('(')
                .append(from.name()).append("_interface_t *interface, ").append(adapter.name()).append("_context_t *context);\n\n");

        out.append(user.render("adapter.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    String renderSource(ProjectConfig project, AdapterSpec adapter, InterfaceSpec from, InterfaceSpec to,
                        Map<String, String> mappings, DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("adapter-source", adapter.source().getFileName().toString())).append('\n');
        out.append(docs.file(adapter.sourceFile(), adapter.description())).append('\n');
        out.append("#include \"").append(adapter.header()).append("\"\n\n");
        out.append(user.render("adapter.source.includes", "")).append('\n');

        out.append(CGenTag.generatedItem("function", "set_target")).append('\n');
        out.append("void ").append(adapter.name()).append("_set_target(").append(adapter.name())
                .append("_context_t *context, const ").append(to.name()).append("_interface_t *target)\n{\n")
                .append(indent(project, 1)).append("context->target = target;\n")
                .append("}\n\n");

        for (InterfaceSpec.Function function : from.functions()) {
            String implementation = adapter.name() + "_" + from.name() + "_" + function.name();
            out.append(CGenTag.generatedItem("private-function", implementation)).append('\n');
            out.append("static ").append(function.returnType()).append(' ').append(implementation).append("(void *context");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append(adapter.name()).append("_context_t *adapter = (")
                    .append(adapter.name()).append("_context_t *)context;\n");
            boolean returnsValue = !function.returnType().equals("void");
            if (returnsValue) {
                out.append(indent(project, 1)).append(function.returnType()).append(" cgen_result = ")
                        .append(function.invalidReturn()).append(";\n");
            }
            String mappedTo = mappings.get(function.name());
            if (mappedTo == null) {
                out.append(indent(project, 1)).append("(void)adapter;\n");
                for (InterfaceSpec.Parameter parameter : function.parameters()) {
                    out.append(indent(project, 1)).append("(void)").append(parameter.name()).append(";\n");
                }
                out.append('\n');
                out.append(user.render("function." + from.name() + "." + function.name() + ".body", ""));
            } else {
                out.append('\n');
                out.append(indent(project, 1));
                if (returnsValue) {
                    out.append("cgen_result = ");
                }
                out.append(to.name()).append('_').append(mappedTo).append("(adapter->target");
                for (InterfaceSpec.Parameter parameter : function.parameters()) {
                    out.append(", ").append(parameter.name());
                }
                out.append(");\n");
            }
            if (returnsValue) {
                out.append(indent(project, 1)).append("return cgen_result;\n");
            }
            out.append("}\n\n");
        }

        String bind = adapter.name() + "_bind_" + from.name();
        out.append(CGenTag.generatedItem("bind-function", bind)).append('\n');
        out.append("void ").append(bind).append('(').append(from.name()).append("_interface_t *interface, ")
                .append(adapter.name()).append("_context_t *context)\n{\n")
                .append(indent(project, 1)).append("if (interface != NULL)\n")
                .append(indent(project, 1)).append("{\n")
                .append(indent(project, 2)).append("interface->context = context;\n");
        for (InterfaceSpec.Function function : from.functions()) {
            out.append(indent(project, 2)).append("interface->").append(function.name()).append(" = ")
                    .append(adapter.name()).append('_').append(from.name()).append('_').append(function.name()).append(";\n");
        }
        out.append(indent(project, 1)).append("}\n}\n\n");

        out.append(user.render("adapter.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }
}
