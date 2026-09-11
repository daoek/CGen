package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;

import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

final class InterfaceRenderer {
    String render(ProjectConfig project, InterfaceSpec spec, DocumentationRenderer docs, UserRegions user) {
        String guard = macro(spec.header());

        StringBuilder out = new StringBuilder();
        appendTop(out, spec, docs, user, guard);
        appendEnums(out, project, spec, docs);
        appendStructs(out, project, spec, docs);
        out.append(user.render("interface.declarations", "")).append('\n');
        appendInterfaceTable(out, project, spec);
        appendDispatchFunctions(out, project, spec, docs);
        appendBottom(out, user, guard);
        return out.toString();
    }

    private static void appendTop(StringBuilder out, InterfaceSpec spec, DocumentationRenderer docs,
                                  UserRegions user, String guard) {
        out.append(CGenTag.generatedFile("interface", spec.source().getFileName().toString())).append('\n');
        out.append(docs.file(spec.header(), spec.description())).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        appendIncludes(out, List.of("<stddef.h>"), spec.includes());
        out.append(user.render("interface.preamble", "")).append('\n');
    }

    private static void appendEnums(StringBuilder out, ProjectConfig project, InterfaceSpec spec, DocumentationRenderer docs) {
        for (InterfaceSpec.EnumDef type : spec.enums()) {
            out.append(CGenTag.generatedItem("enum", type.name())).append('\n');
            out.append(docs.type(type.name(), type.description()));
            out.append("typedef enum\n{\n");
            for (int index = 0; index < type.values().size(); index++) {
                InterfaceSpec.EnumValue value = type.values().get(index);
                out.append(indent(project, 1)).append(value.name());
                if (value.value() != null) {
                    out.append(" = ").append(value.value());
                }
                if (index + 1 < type.values().size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append("} ").append(type.name()).append(";\n\n");
        }
    }

    private static void appendStructs(StringBuilder out, ProjectConfig project, InterfaceSpec spec, DocumentationRenderer docs) {
        for (InterfaceSpec.StructDef type : spec.structs()) {
            out.append(CGenTag.generatedItem("struct", type.name())).append('\n');
            out.append(docs.type(type.name(), type.description()));
            out.append("typedef struct\n{\n");
            for (InterfaceSpec.Field field : type.fields()) {
                out.append(indent(project, 1));
                appendTypedName(out, field.type(), field.name());
                out.append(";\n");
            }
            out.append("} ").append(type.name()).append(";\n\n");
        }
    }

    private static void appendInterfaceTable(StringBuilder out, ProjectConfig project, InterfaceSpec spec) {
        out.append(CGenTag.generatedItem("interface-table", spec.name())).append('\n');
        out.append("typedef struct\n{\n").append(indent(project, 1)).append("void *context;\n");
        for (InterfaceSpec.Function function : spec.functions()) {
            out.append(indent(project, 1)).append(function.returnType()).append(" (*").append(function.name())
                    .append(")(void *context");
            appendParameters(out, function.parameters(), true);
            out.append(");\n");
        }
        out.append("} ").append(spec.name()).append("_interface_t;\n\n");
    }

    private static void appendDispatchFunctions(StringBuilder out, ProjectConfig project, InterfaceSpec spec, DocumentationRenderer docs) {
        for (InterfaceSpec.Function function : spec.functions()) {
            out.append(CGenTag.generatedItem("function", function.name())).append('\n');
            out.append(docs.function(spec.name() + "_" + function.name(), function.description(), function.returnType(), function.parameters()));
            out.append("static inline ").append(function.returnType()).append(' ').append(spec.name()).append('_')
                    .append(function.name()).append("(const ").append(spec.name()).append("_interface_t * const interface");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            appendDispatchBody(out, project, function);
            out.append("}\n\n");
        }
    }

    private static void appendBottom(StringBuilder out, UserRegions user, String guard) {
        out.append(user.render("interface.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
    }

    private static void appendDispatchBody(StringBuilder out, ProjectConfig project, InterfaceSpec.Function function) {
        boolean returnsValue = !function.returnType().equals("void");
        if (returnsValue) {
            out.append(indent(project, 1)).append(function.returnType()).append(" cgen_result = ")
                    .append(function.invalidReturn()).append(";\n\n");
        }
        out.append(indent(project, 1)).append("if (interface != NULL)\n")
                .append(indent(project, 1)).append("{\n")
                .append(indent(project, 2)).append("if ((interface->context != NULL) && (interface->")
                .append(function.name()).append(" != NULL))\n")
                .append(indent(project, 2)).append("{\n")
                .append(indent(project, 3));
        if (returnsValue) {
            out.append("cgen_result = ");
        }
        appendInterfaceCall(out, function);
        out.append(";\n").append(indent(project, 2)).append("}");
        if (returnsValue) {
            out.append("\n").append(indent(project, 2)).append("else\n")
                    .append(indent(project, 2)).append("{\n")
                    .append(indent(project, 3)).append("cgen_result = ").append(function.uninitializedReturn()).append(";\n")
                    .append(indent(project, 2)).append("}");
        }
        out.append("\n").append(indent(project, 1)).append("}\n");
        if (returnsValue) {
            out.append("\n").append(indent(project, 1)).append("return cgen_result;\n");
        }
    }

    private static void appendInterfaceCall(StringBuilder out, InterfaceSpec.Function function) {
        out.append("interface->").append(function.name()).append("(interface->context");
        for (InterfaceSpec.Parameter parameter : function.parameters()) {
            out.append(", ").append(parameter.name());
        }
        out.append(')');
    }
}
