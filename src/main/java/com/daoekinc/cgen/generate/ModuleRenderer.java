package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;
import static com.daoekinc.cgen.generate.RenderSupport.quotedRelative;

import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ModuleSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

final class ModuleRenderer {
    String renderHeader(ProjectConfig project, ModuleSpec module, List<InterfaceSpec> interfaces,
                        DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("module-header", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.header(), module.description())).append('\n');
        String guard = macro(module.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        List<String> contractIncludes = interfaces.stream()
                .map(spec -> quotedRelative(module.source().getParent(), spec.source().getParent().resolve(spec.header())))
                .toList();
        appendIncludes(out, contractIncludes, module.includes());
        out.append(user.render("module.header.preamble", "")).append('\n');

        for (ModuleSpec.Variable variable : module.variables()) {
            if (variable.visibility() == ModuleSpec.Visibility.PUBLIC) {
                out.append(CGenTag.generatedItem("public-variable", variable.name())).append('\n');
                out.append(docs.variable(variable.name(), variable.description()));
                out.append("extern ");
                appendTypedName(out, variable.type(), variable.name());
                out.append(";\n\n");
            }
        }
        out.append(CGenTag.generatedItem("context", module.name())).append('\n');
        out.append("typedef struct\n{\n");
        if (module.context().isEmpty()) {
            out.append(indent(project, 1)).append("unsigned char reserved;\n");
        } else {
            for (InterfaceSpec.Field field : module.context()) {
                out.append(indent(project, 1));
                appendTypedName(out, field.type(), field.name());
                out.append(";\n");
            }
        }
        out.append("} ").append(module.name()).append("_context_t;\n\n");

        for (InterfaceSpec contract : interfaces) {
            String function = module.name() + "_bind_" + contract.name();
            out.append(CGenTag.generatedItem("bind-function", function)).append('\n');
            out.append("void ").append(function).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context);\n\n");
        }
        out.append(user.render("module.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    String renderSource(ProjectConfig project, ModuleSpec module, List<InterfaceSpec> interfaces,
                        DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("module-source", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.sourceFile(), module.description())).append('\n');
        out.append("#include \"").append(module.header()).append("\"\n\n");
        out.append(user.render("module.source.includes", "")).append('\n');
        for (ModuleSpec.Variable variable : module.variables()) {
            out.append(CGenTag.generatedItem(variable.visibility() == ModuleSpec.Visibility.PRIVATE ? "private-variable" : "variable-definition", variable.name())).append('\n');
            out.append(docs.variable(variable.name(), variable.description()));
            if (variable.visibility() == ModuleSpec.Visibility.PRIVATE) {
                out.append("static ");
            }
            appendTypedName(out, variable.type(), variable.name());
            if (variable.initial() != null) {
                out.append(" = ").append(variable.initial());
            }
            out.append(";\n\n");
        }

        for (InterfaceSpec contract : interfaces) {
            for (InterfaceSpec.Function function : contract.functions()) {
                String implementation = module.name() + "_" + contract.name() + "_" + function.name();
                out.append(CGenTag.generatedItem("private-function", implementation)).append('\n');
                out.append("static ").append(function.returnType()).append(' ').append(implementation).append("(void *context");
                appendParameters(out, function.parameters(), true);
                out.append(")\n{\n");
                out.append(indent(project, 1)).append(module.name()).append("_context_t *module = (")
                        .append(module.name()).append("_context_t *)context;\n");
                if (!function.returnType().equals("void")) {
                    out.append(indent(project, 1)).append(function.returnType()).append(" cgen_result = ")
                            .append(function.invalidReturn()).append(";\n");
                }
                out.append(indent(project, 1)).append("(void)module;\n");
                for (InterfaceSpec.Parameter parameter : function.parameters()) {
                    out.append(indent(project, 1)).append("(void)").append(parameter.name()).append(";\n");
                }
                out.append('\n');
                out.append(user.render("function." + contract.name() + "." + function.name() + ".body", ""));
                if (!function.returnType().equals("void")) {
                    out.append(indent(project, 1)).append("return cgen_result;\n");
                }
                out.append("}\n\n");
            }
            String bind = module.name() + "_bind_" + contract.name();
            out.append(CGenTag.generatedItem("bind-function", bind)).append('\n');
            out.append("void ").append(bind).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context)\n{\n")
                    .append(indent(project, 1)).append("if (interface != NULL)\n")
                    .append(indent(project, 1)).append("{\n")
                    .append(indent(project, 2)).append("interface->context = context;\n");
            for (InterfaceSpec.Function function : contract.functions()) {
                out.append(indent(project, 2)).append("interface->").append(function.name()).append(" = ")
                        .append(module.name()).append('_').append(contract.name()).append('_').append(function.name()).append(";\n");
            }
            out.append(indent(project, 1)).append("}\n}\n\n");
        }
        out.append(user.render("module.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }
}
