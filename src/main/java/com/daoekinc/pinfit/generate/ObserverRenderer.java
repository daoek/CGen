package com.daoekinc.pinfit.generate;

import static com.daoekinc.pinfit.generate.RenderSupport.appendIncludes;
import static com.daoekinc.pinfit.generate.RenderSupport.appendParameters;
import static com.daoekinc.pinfit.generate.RenderSupport.appendTypedName;
import static com.daoekinc.pinfit.generate.RenderSupport.functionName;
import static com.daoekinc.pinfit.generate.RenderSupport.indent;
import static com.daoekinc.pinfit.generate.RenderSupport.macro;
import static com.daoekinc.pinfit.generate.RenderSupport.quotedRelative;

import com.daoekinc.pinfit.model.InterfaceSpec;
import com.daoekinc.pinfit.model.ObserverSpec;
import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.tag.PinfitTag;
import com.daoekinc.pinfit.tag.TagHelper.UserRegions;
import java.util.List;

final class ObserverRenderer {
    String renderHeader(ProjectConfig project, ObserverSpec observer, InterfaceSpec listener,
                        DocumentationRenderer docs, UserRegions user) {
        String guard = macro(observer.header());
        String capacityMacro = capacityMacro(observer);

        StringBuilder out = new StringBuilder();
        appendHeaderTop(out, observer, listener, docs, user, guard);
        appendCapacityMacro(out, observer, capacityMacro);
        appendHeaderContextStruct(out, project, observer, listener, capacityMacro);
        appendCoreFunctionDeclarations(out, project, observer, listener);
        appendPublishFunctionDeclarations(out, project, observer, listener, docs);
        appendHeaderBottom(out, user, guard);
        return out.toString();
    }

    private static void appendHeaderTop(StringBuilder out, ObserverSpec observer, InterfaceSpec listener,
                                        DocumentationRenderer docs, UserRegions user, String guard) {
        out.append(PinfitTag.generatedFile("observer-header", observer.source().getFileName().toString())).append('\n');
        out.append(docs.file(observer.header(), observer.description())).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        String listenerInclude = quotedRelative(observer.source().getParent(),
                listener.source().getParent().resolve(listener.header()));
        appendIncludes(out, List.of("<stdbool.h>", "<stdint.h>", listenerInclude), observer.includes());
        out.append(user.render("observer.header.preamble", "")).append('\n');
    }

    private static void appendCapacityMacro(StringBuilder out, ObserverSpec observer, String capacityMacro) {
        out.append(PinfitTag.generatedItem("macro", capacityMacro)).append('\n');
        out.append("#define ").append(capacityMacro).append(" ").append(observer.capacity()).append("u\n\n");
    }

    private static void appendHeaderContextStruct(StringBuilder out, ProjectConfig project, ObserverSpec observer,
                                                   InterfaceSpec listener, String capacityMacro) {
        out.append(PinfitTag.generatedItem("context", observer.name())).append('\n');
        out.append("typedef struct\n{\n");
        out.append(indent(project, 1)).append("const ").append(listener.name()).append("_interface_t *subscribers[")
                .append(capacityMacro).append("];\n");
        out.append(indent(project, 1)).append("uint32_t count;\n");
        for (InterfaceSpec.Field field : observer.context()) {
            out.append(indent(project, 1));
            appendTypedName(out, field.type(), field.name());
            out.append(";\n");
        }
        out.append("} ").append(observer.name()).append("_context_t;\n\n");
    }

    private static void appendCoreFunctionDeclarations(StringBuilder out, ProjectConfig project, ObserverSpec observer,
                                                        InterfaceSpec listener) {
        String init = functionName(project, observer.name(), "init");
        out.append(PinfitTag.generatedItem("function", init)).append('\n');
        out.append("void ").append(init).append('(').append(observer.name()).append("_context_t *context);\n\n");

        String subscribe = functionName(project, observer.name(), "subscribe");
        out.append(PinfitTag.generatedItem("function", subscribe)).append('\n');
        out.append("bool ").append(subscribe).append('(').append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber);\n\n");

        String unsubscribe = functionName(project, observer.name(), "unsubscribe");
        out.append(PinfitTag.generatedItem("function", unsubscribe)).append('\n');
        out.append("bool ").append(unsubscribe).append('(').append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber);\n\n");
    }

    private static void appendPublishFunctionDeclarations(StringBuilder out, ProjectConfig project, ObserverSpec observer,
                                                           InterfaceSpec listener, DocumentationRenderer docs) {
        for (InterfaceSpec.Function function : listener.functions()) {
            String publish = functionName(project, observer.name(), "publish", function.name());
            out.append(PinfitTag.generatedItem("function", publish)).append('\n');
            out.append(docs.function(publish, function.description(), "void", function.parameters()));
            out.append("void ").append(publish).append('(').append(observer.name()).append("_context_t *context");
            appendParameters(out, function.parameters(), true);
            out.append(");\n\n");
        }
    }

    private static void appendHeaderBottom(StringBuilder out, UserRegions user, String guard) {
        out.append(user.render("observer.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
    }

    String renderSource(ProjectConfig project, ObserverSpec observer, InterfaceSpec listener,
                        DocumentationRenderer docs, UserRegions user) {
        String capacityMacro = capacityMacro(observer);

        StringBuilder out = new StringBuilder();
        appendSourceTop(out, observer, docs, user);
        appendInitFunction(out, project, observer);
        appendSubscribeFunction(out, project, observer, listener, capacityMacro);
        appendUnsubscribeFunction(out, project, observer, listener);
        appendPublishFunctions(out, project, observer, listener);
        appendSourceBottom(out, user);
        return out.toString();
    }

    private static void appendSourceTop(StringBuilder out, ObserverSpec observer, DocumentationRenderer docs, UserRegions user) {
        out.append(PinfitTag.generatedFile("observer-source", observer.source().getFileName().toString())).append('\n');
        out.append(docs.file(observer.sourceFile(), observer.description())).append('\n');
        out.append("#include \"").append(observer.header()).append("\"\n");
        out.append("#include <stddef.h>\n\n");
        out.append(user.render("observer.source.includes", "")).append('\n');
    }

    private static void appendInitFunction(StringBuilder out, ProjectConfig project, ObserverSpec observer) {
        String init = functionName(project, observer.name(), "init");
        out.append(PinfitTag.generatedItem("function", init)).append('\n');
        out.append("void ").append(init).append('(').append(observer.name()).append("_context_t *context)\n{\n")
                .append(indent(project, 1)).append("context->count = 0U;\n")
                .append("}\n\n");
    }

    private static void appendSubscribeFunction(StringBuilder out, ProjectConfig project, ObserverSpec observer,
                                                InterfaceSpec listener, String capacityMacro) {
        String subscribe = functionName(project, observer.name(), "subscribe");
        out.append(PinfitTag.generatedItem("function", subscribe)).append('\n');
        out.append("bool ").append(subscribe).append('(').append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber)\n{\n");
        out.append(indent(project, 1)).append("bool pinfit_result = false;\n\n");
        out.append(indent(project, 1)).append("if ((subscriber != NULL) && (context->count < ").append(capacityMacro).append("))\n");
        out.append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append("uint32_t index;\n");
        out.append(indent(project, 2)).append("bool already_subscribed = false;\n\n");
        out.append(indent(project, 2)).append("for (index = 0U; index < context->count; index++)\n");
        out.append(indent(project, 2)).append("{\n");
        out.append(indent(project, 3)).append("if (context->subscribers[index] == subscriber)\n");
        out.append(indent(project, 3)).append("{\n");
        out.append(indent(project, 4)).append("already_subscribed = true;\n");
        out.append(indent(project, 3)).append("}\n");
        out.append(indent(project, 2)).append("}\n\n");
        out.append(indent(project, 2)).append("if (!already_subscribed)\n");
        out.append(indent(project, 2)).append("{\n");
        out.append(indent(project, 3)).append("context->subscribers[context->count] = subscriber;\n");
        out.append(indent(project, 3)).append("context->count++;\n");
        out.append(indent(project, 3)).append("pinfit_result = true;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n\n");
        out.append(indent(project, 1)).append("return pinfit_result;\n");
        out.append("}\n\n");
    }

    private static void appendUnsubscribeFunction(StringBuilder out, ProjectConfig project, ObserverSpec observer, InterfaceSpec listener) {
        String unsubscribe = functionName(project, observer.name(), "unsubscribe");
        out.append(PinfitTag.generatedItem("function", unsubscribe)).append('\n');
        out.append("bool ").append(unsubscribe).append('(').append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber)\n{\n");
        out.append(indent(project, 1)).append("bool pinfit_result = false;\n");
        out.append(indent(project, 1)).append("uint32_t index;\n\n");
        out.append(indent(project, 1)).append("for (index = 0U; index < context->count; index++)\n");
        out.append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append("if (context->subscribers[index] == subscriber)\n");
        out.append(indent(project, 2)).append("{\n");
        out.append(indent(project, 3)).append("context->count--;\n");
        out.append(indent(project, 3)).append("context->subscribers[index] = context->subscribers[context->count];\n");
        out.append(indent(project, 3)).append("pinfit_result = true;\n");
        out.append(indent(project, 3)).append("break;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n\n");
        out.append(indent(project, 1)).append("return pinfit_result;\n");
        out.append("}\n\n");
    }

    private static void appendPublishFunctions(StringBuilder out, ProjectConfig project, ObserverSpec observer, InterfaceSpec listener) {
        for (InterfaceSpec.Function function : listener.functions()) {
            String publish = functionName(project, observer.name(), "publish", function.name());
            out.append(PinfitTag.generatedItem("function", publish)).append('\n');
            out.append("void ").append(publish).append('(').append(observer.name()).append("_context_t *context");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append("uint32_t index;\n\n");
            out.append(indent(project, 1)).append("for (index = 0U; index < context->count; index++)\n");
            out.append(indent(project, 1)).append("{\n");
            out.append(indent(project, 2)).append(functionName(project, listener.name(), function.name()))
                    .append("(context->subscribers[index]");
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                out.append(", ").append(parameter.name());
            }
            out.append(");\n");
            out.append(indent(project, 1)).append("}\n");
            out.append("}\n\n");
        }
    }

    private static void appendSourceBottom(StringBuilder out, UserRegions user) {
        out.append(user.render("observer.source.footer", ""));
        out.append(user.renderOrphans());
    }

    private static String capacityMacro(ObserverSpec observer) {
        return observer.name().toUpperCase() + "_CAPACITY";
    }
}
