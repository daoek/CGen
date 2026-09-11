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
        boolean accessors = project.publicVariableStyle().equals("accessors");
        String guard = macro(module.header());

        StringBuilder out = new StringBuilder();
        appendHeaderTop(out, module, interfaces, docs, user, guard);
        appendPublicVariableDeclarations(out, project, module, docs, accessors);
        appendContextStruct(out, project, module);
        appendBindFunctionDeclarations(out, module, interfaces);
        appendSingletonDeclaration(out, module);
        appendHeaderBottom(out, user, guard);
        return out.toString();
    }

    private static void appendHeaderTop(StringBuilder out, ModuleSpec module, List<InterfaceSpec> interfaces,
                                        DocumentationRenderer docs, UserRegions user, String guard) {
        out.append(CGenTag.generatedFile("module-header", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.header(), module.description())).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        List<String> contractIncludes = interfaces.stream()
                .map(spec -> quotedRelative(module.source().getParent(), spec.source().getParent().resolve(spec.header())))
                .toList();
        appendIncludes(out, contractIncludes, module.includes());
        out.append(user.render("module.header.preamble", "")).append('\n');
    }

    private static void appendPublicVariableDeclarations(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                          DocumentationRenderer docs, boolean accessors) {
        for (ModuleSpec.Variable variable : module.variables()) {
            if (variable.visibility() != ModuleSpec.Visibility.PUBLIC) {
                continue;
            }
            if (accessors) {
                out.append(CGenTag.generatedItem("public-accessor", variable.name())).append('\n');
                out.append(docs.variable(variable.name(), variable.description()));
                appendAccessorSignature(out, module, variable, true);
                out.append(";\n");
                appendAccessorSignature(out, module, variable, false);
                out.append(";\n\n");
            } else {
                out.append(CGenTag.generatedItem("public-variable", variable.name())).append('\n');
                out.append(docs.variable(variable.name(), variable.description()));
                out.append("extern ");
                appendTypedName(out, variable.type(), variable.name());
                out.append(";\n\n");
            }
        }
    }

    private static void appendContextStruct(StringBuilder out, ProjectConfig project, ModuleSpec module) {
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
    }

    private static void appendBindFunctionDeclarations(StringBuilder out, ModuleSpec module, List<InterfaceSpec> interfaces) {
        for (InterfaceSpec contract : interfaces) {
            String function = module.name() + "_bind_" + contract.name();
            out.append(CGenTag.generatedItem("bind-function", function)).append('\n');
            out.append("void ").append(function).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context);\n\n");
        }
    }

    private static void appendSingletonDeclaration(StringBuilder out, ModuleSpec module) {
        if (module.singleton()) {
            out.append(CGenTag.generatedItem("function", module.name() + "_instance")).append('\n');
            out.append(module.name()).append("_context_t *").append(module.name()).append("_instance(void);\n\n");
        }
    }

    private static void appendHeaderBottom(StringBuilder out, UserRegions user, String guard) {
        out.append(user.render("module.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
    }

    String renderSource(ProjectConfig project, ModuleSpec module, List<InterfaceSpec> interfaces,
                        DocumentationRenderer docs, UserRegions user) {
        boolean accessors = project.publicVariableStyle().equals("accessors");

        StringBuilder out = new StringBuilder();
        appendSourceTop(out, module, docs, user);
        appendVariableDefinitions(out, project, module, docs, accessors);
        appendAccessorDefinitions(out, project, module, user, accessors);
        for (InterfaceSpec contract : interfaces) {
            appendContractImplementation(out, project, module, contract, user);
        }
        appendSingletonDefinition(out, project, module, user);
        appendSourceBottom(out, user);
        return out.toString();
    }

    private static void appendSourceTop(StringBuilder out, ModuleSpec module, DocumentationRenderer docs, UserRegions user) {
        out.append(CGenTag.generatedFile("module-source", module.source().getFileName().toString())).append('\n');
        out.append(docs.file(module.sourceFile(), module.description())).append('\n');
        out.append("#include \"").append(module.header()).append("\"\n");
        if (module.singleton()) {
            out.append("#include <stdbool.h>\n");
        }
        out.append('\n');
        out.append(user.render("module.source.includes", "")).append('\n');
    }

    private static void appendVariableDefinitions(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                   DocumentationRenderer docs, boolean accessors) {
        for (ModuleSpec.Variable variable : module.variables()) {
            boolean privateStorage = variable.visibility() == ModuleSpec.Visibility.PRIVATE
                    || (variable.visibility() == ModuleSpec.Visibility.PUBLIC && accessors);
            out.append(CGenTag.generatedItem(privateStorage ? "private-variable" : "variable-definition", variable.name())).append('\n');
            out.append(docs.variable(variable.name(), variable.description()));
            if (privateStorage) {
                out.append("static ");
            }
            appendTypedName(out, variable.type(), variable.name());
            if (variable.initial() != null) {
                out.append(" = ").append(variable.initial());
            }
            out.append(";\n\n");
        }
    }

    private static void appendAccessorDefinitions(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                   UserRegions user, boolean accessors) {
        if (!accessors) {
            return;
        }
        for (ModuleSpec.Variable variable : module.variables()) {
            if (variable.visibility() != ModuleSpec.Visibility.PUBLIC) {
                continue;
            }
            out.append(CGenTag.generatedItem("public-accessor", variable.name())).append('\n');
            appendAccessorSignature(out, module, variable, true);
            out.append("\n{\n");
            out.append(user.render("variable." + variable.name() + ".get", indent(project, 1) + "return " + variable.name() + ";"));
            out.append("}\n\n");
            appendAccessorSignature(out, module, variable, false);
            out.append("\n{\n");
            out.append(user.render("variable." + variable.name() + ".set", indent(project, 1) + variable.name() + " = value;"));
            out.append("}\n\n");
        }
    }

    private static void appendContractImplementation(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                      InterfaceSpec contract, UserRegions user) {
        for (InterfaceSpec.Function function : contract.functions()) {
            String implementation = module.name() + "_" + contract.name() + "_" + function.name();
            out.append(CGenTag.generatedItem("private-function", implementation)).append('\n');
            out.append("static ").append(function.returnType()).append(' ').append(implementation).append("(void *context");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append(module.name()).append("_context_t *module = (")
                    .append(module.name()).append("_context_t *)context;\n");
            boolean returnsValue = !function.returnType().equals("void");
            if (returnsValue) {
                out.append(indent(project, 1)).append(function.returnType()).append(" cgen_result = ")
                        .append(function.invalidReturn()).append(";\n");
            }
            out.append(indent(project, 1)).append("(void)module;\n");
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                out.append(indent(project, 1)).append("(void)").append(parameter.name()).append(";\n");
            }
            out.append('\n');
            out.append(user.render("function." + contract.name() + "." + function.name() + ".body", ""));
            if (returnsValue) {
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

    private static void appendSingletonDefinition(StringBuilder out, ProjectConfig project, ModuleSpec module, UserRegions user) {
        if (!module.singleton()) {
            return;
        }
        out.append(CGenTag.generatedItem("function", module.name() + "_instance")).append('\n');
        out.append("static ").append(module.name()).append("_context_t ").append(module.name()).append("_singleton_context;\n");
        out.append("static bool ").append(module.name()).append("_singleton_initialized = false;\n\n");
        out.append(module.name()).append("_context_t *").append(module.name()).append("_instance(void)\n{\n")
                .append(indent(project, 1)).append("if (!").append(module.name()).append("_singleton_initialized)\n")
                .append(indent(project, 1)).append("{\n")
                .append(indent(project, 2)).append(module.name()).append("_singleton_initialized = true;\n");
        out.append(user.render("singleton.init", ""));
        out.append(indent(project, 1)).append("}\n")
                .append(indent(project, 1)).append("return &").append(module.name()).append("_singleton_context;\n")
                .append("}\n\n");
    }

    private static void appendSourceBottom(StringBuilder out, UserRegions user) {
        out.append(user.render("module.source.footer", ""));
        out.append(user.renderOrphans());
    }

    private static void appendAccessorSignature(StringBuilder out, ModuleSpec module, ModuleSpec.Variable variable, boolean isGetter) {
        if (isGetter) {
            out.append(variable.type());
            if (!variable.type().stripTrailing().endsWith("*")) {
                out.append(' ');
            }
            out.append(module.name()).append("_get_").append(variable.name()).append("(void)");
        } else {
            out.append("void ").append(module.name()).append("_set_").append(variable.name()).append('(');
            appendTypedName(out, variable.type(), "value");
            out.append(')');
        }
    }
}
