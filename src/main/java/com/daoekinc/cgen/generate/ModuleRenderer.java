package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendEnums;
import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.appendUnusedSilencer;
import static com.daoekinc.cgen.generate.RenderSupport.functionName;
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
        appendEnums(out, project, module.enums(), docs);
        appendPublicVariableDeclarations(out, project, module, docs, accessors);
        appendContextStruct(out, project, module, interfaces);
        appendBindFunctionDeclarations(out, project, module, interfaces);
        appendFunctionDeclarations(out, project, module, docs);
        appendSingletonDeclaration(out, module, interfaces);
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
                appendAccessorSignature(out, project, module, variable, true);
                out.append(";\n");
                appendAccessorSignature(out, project, module, variable, false);
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

    private static void appendContextStruct(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                             List<InterfaceSpec> interfaces) {
        boolean needed = !module.context().isEmpty() || !interfaces.isEmpty()
                || (module.singleton() && !isRunOnceSingleton(module, interfaces));
        if (!needed) {
            return;
        }
        out.append(CGenTag.generatedItem("context", module.name())).append('\n');
        if (module.context().isEmpty() && !module.singleton()) {
            out.append("typedef void ").append(module.name()).append("_context_t;\n\n");
            return;
        }
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

    private static void appendBindFunctionDeclarations(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                        List<InterfaceSpec> interfaces) {
        for (InterfaceSpec contract : interfaces) {
            String function = functionName(project, module.name(), "bind", contract.name());
            out.append(CGenTag.generatedItem("bind-function", function)).append('\n');
            out.append("void ").append(function).append('(').append(contract.name()).append("_interface_t *interface, ")
                    .append(module.name()).append("_context_t *context);\n\n");
        }
    }

    private static void appendFunctionDeclarations(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                   DocumentationRenderer docs) {
        for (ModuleSpec.Function moduleFunction : module.functions()) {
            if (moduleFunction.visibility() != ModuleSpec.Visibility.PUBLIC) {
                continue;
            }
            InterfaceSpec.Function function = moduleFunction.spec();
            String name = functionName(project, function.name());
            out.append(CGenTag.generatedItem("function", name)).append('\n');
            out.append(docs.function(name, function.description(), function.returnType(), function.parameters()));
            out.append(function.returnType()).append(' ').append(name).append('(');
            if (function.parameters().isEmpty()) {
                out.append("void");
            } else {
                appendParameters(out, function.parameters(), false);
            }
            out.append(");\n\n");
        }
    }

    private static void appendSingletonDeclaration(StringBuilder out, ModuleSpec module, List<InterfaceSpec> interfaces) {
        if (!module.singleton()) {
            return;
        }
        out.append(CGenTag.generatedItem("function", module.instanceName())).append('\n');
        if (isRunOnceSingleton(module, interfaces)) {
            out.append("void ").append(module.instanceName()).append("(void);\n\n");
        } else {
            out.append(module.name()).append("_context_t *").append(module.instanceName()).append("(void);\n\n");
        }
    }

    // A singleton with no context fields and no bound interfaces has nothing to hand out a
    // pointer to; it degenerates into a plain run-once function (e.g. one-time startup code).
    private static boolean isRunOnceSingleton(ModuleSpec module, List<InterfaceSpec> interfaces) {
        return module.singleton() && module.context().isEmpty() && interfaces.isEmpty();
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
        appendPrivateFunctionPrototypes(out, project, module);
        appendVariableDefinitions(out, project, module, docs, accessors);
        appendAccessorDefinitions(out, project, module, user, accessors);
        for (InterfaceSpec contract : interfaces) {
            appendContractImplementation(out, project, module, contract, user);
        }
        appendFunctionDefinitions(out, project, module, user);
        appendSingletonDefinition(out, project, module, user, interfaces);
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
        out.append(user.render("module.source.variables", "")).append('\n');
        out.append(user.render("module.source.prototypes", "")).append('\n');
    }

    private static void appendPrivateFunctionPrototypes(StringBuilder out, ProjectConfig project, ModuleSpec module) {
        List<ModuleSpec.Function> privateFunctions = module.functions().stream()
                .filter(function -> function.visibility() != ModuleSpec.Visibility.PUBLIC).toList();
        if (privateFunctions.isEmpty()) {
            return;
        }
        out.append(CGenTag.generatedItem("function-prototypes", module.name())).append('\n');
        for (ModuleSpec.Function moduleFunction : privateFunctions) {
            InterfaceSpec.Function function = moduleFunction.spec();
            String name = functionName(project, function.name());
            out.append("static ").append(function.returnType()).append(' ').append(name).append('(');
            if (function.parameters().isEmpty()) {
                out.append("void");
            } else {
                appendParameters(out, function.parameters(), false);
            }
            out.append(");\n");
        }
        out.append('\n');
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
            appendAccessorSignature(out, project, module, variable, true);
            out.append("\n{\n");
            out.append(user.render("variable." + variable.name() + ".get", indent(project, 1) + "return " + variable.name() + ";",
                    indent(project, 1)));
            out.append("}\n\n");
            appendAccessorSignature(out, project, module, variable, false);
            out.append("\n{\n");
            out.append(user.render("variable." + variable.name() + ".set", indent(project, 1) + variable.name() + " = value;",
                    indent(project, 1)));
            out.append("}\n\n");
        }
    }

    private static void appendContractImplementation(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                      InterfaceSpec contract, UserRegions user) {
        for (InterfaceSpec.Function function : contract.functions()) {
            String implementation = functionName(project, module.name(), contract.name(), function.name());
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
            appendUnusedSilencer(out, project, 1, "module");
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                appendUnusedSilencer(out, project, 1, parameter.name());
            }
            out.append('\n');
            out.append(user.render("function." + contract.name() + "." + function.name() + ".body", "", indent(project, 1)));
            if (returnsValue) {
                out.append(indent(project, 1)).append("return cgen_result;\n");
            }
            out.append("}\n\n");
        }

        String bind = functionName(project, module.name(), "bind", contract.name());
        out.append(CGenTag.generatedItem("bind-function", bind)).append('\n');
        out.append("void ").append(bind).append('(').append(contract.name()).append("_interface_t *interface, ")
                .append(module.name()).append("_context_t *context)\n{\n")
                .append(indent(project, 1)).append("if (interface != NULL)\n")
                .append(indent(project, 1)).append("{\n")
                .append(indent(project, 2)).append("interface->context = context;\n");
        for (InterfaceSpec.Function function : contract.functions()) {
            out.append(indent(project, 2)).append("interface->").append(function.name()).append(" = ")
                    .append(functionName(project, module.name(), contract.name(), function.name())).append(";\n");
        }
        out.append(indent(project, 1)).append("}\n}\n\n");
    }

    private static void appendFunctionDefinitions(StringBuilder out, ProjectConfig project, ModuleSpec module, UserRegions user) {
        for (ModuleSpec.Function moduleFunction : module.functions()) {
            InterfaceSpec.Function function = moduleFunction.spec();
            String name = functionName(project, function.name());
            out.append(CGenTag.generatedItem("function", name)).append('\n');
            if (moduleFunction.visibility() != ModuleSpec.Visibility.PUBLIC) {
                out.append("static ");
            }
            out.append(function.returnType()).append(' ').append(name).append('(');
            if (function.parameters().isEmpty()) {
                out.append("void");
            } else {
                appendParameters(out, function.parameters(), false);
            }
            out.append(")\n{\n");
            boolean returnsValue = !function.returnType().equals("void");
            if (returnsValue) {
                out.append(indent(project, 1)).append(function.returnType()).append(" cgen_result = ")
                        .append(function.invalidReturn()).append(";\n");
            }
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                appendUnusedSilencer(out, project, 1, parameter.name());
            }
            out.append('\n');
            out.append(user.render("function." + function.name() + ".body", "", indent(project, 1)));
            if (returnsValue) {
                out.append(indent(project, 1)).append("return cgen_result;\n");
            }
            out.append("}\n\n");
        }
    }

    private static void appendSingletonDefinition(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                  UserRegions user, List<InterfaceSpec> interfaces) {
        if (!module.singleton()) {
            return;
        }
        out.append(CGenTag.generatedItem("function", module.instanceName())).append('\n');
        if (isRunOnceSingleton(module, interfaces)) {
            out.append("static bool ").append(module.name()).append("_singleton_initialized = false;\n\n");
            out.append("void ").append(module.instanceName()).append("(void)\n{\n")
                    .append(indent(project, 1)).append("if (!").append(module.name()).append("_singleton_initialized)\n")
                    .append(indent(project, 1)).append("{\n")
                    .append(indent(project, 2)).append(module.name()).append("_singleton_initialized = true;\n");
            out.append(user.render("singleton.init", "", indent(project, 2)));
            out.append(indent(project, 1)).append("}\n");
            appendSingletonElse(out, project, module, user);
            out.append("}\n\n");
            return;
        }
        out.append("static ").append(module.name()).append("_context_t ").append(module.name()).append("_singleton_context;\n");
        out.append("static bool ").append(module.name()).append("_singleton_initialized = false;\n\n");
        out.append(module.name()).append("_context_t *").append(module.instanceName()).append("(void)\n{\n")
                .append(indent(project, 1)).append("if (!").append(module.name()).append("_singleton_initialized)\n")
                .append(indent(project, 1)).append("{\n")
                .append(indent(project, 2)).append(module.name()).append("_singleton_initialized = true;\n");
        out.append(user.render("singleton.init", "", indent(project, 2)));
        out.append(indent(project, 1)).append("}\n");
        appendSingletonElse(out, project, module, user);
        out.append(indent(project, 1)).append("return &").append(module.name()).append("_singleton_context;\n")
                .append("}\n\n");
    }

    private static void appendSingletonElse(StringBuilder out, ProjectConfig project, ModuleSpec module, UserRegions user) {
        if (!module.singletonElse()) {
            return;
        }
        out.append(indent(project, 1)).append("else\n")
                .append(indent(project, 1)).append("{\n");
        out.append(user.render("singleton.else", "", indent(project, 2)));
        out.append(indent(project, 1)).append("}\n");
    }

    private static void appendSourceBottom(StringBuilder out, UserRegions user) {
        out.append(user.render("module.source.footer", ""));
        out.append(user.renderOrphans());
    }

    private static void appendAccessorSignature(StringBuilder out, ProjectConfig project, ModuleSpec module,
                                                ModuleSpec.Variable variable, boolean isGetter) {
        if (isGetter) {
            out.append(variable.type());
            if (!variable.type().stripTrailing().endsWith("*")) {
                out.append(' ');
            }
            out.append(functionName(project, module.name(), "get", variable.name())).append("(void)");
        } else {
            out.append("void ").append(functionName(project, module.name(), "set", variable.name())).append('(');
            appendTypedName(out, variable.type(), "value");
            out.append(')');
        }
    }
}
