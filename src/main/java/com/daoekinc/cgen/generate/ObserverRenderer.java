package com.daoekinc.cgen.generate;

import static com.daoekinc.cgen.generate.RenderSupport.appendIncludes;
import static com.daoekinc.cgen.generate.RenderSupport.appendParameters;
import static com.daoekinc.cgen.generate.RenderSupport.appendTypedName;
import static com.daoekinc.cgen.generate.RenderSupport.indent;
import static com.daoekinc.cgen.generate.RenderSupport.macro;
import static com.daoekinc.cgen.generate.RenderSupport.quotedRelative;

import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ObserverSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import com.daoekinc.cgen.tag.CGenTag;
import com.daoekinc.cgen.tag.TagHelper.UserRegions;
import java.util.List;

final class ObserverRenderer {
    String renderHeader(ProjectConfig project, ObserverSpec observer, InterfaceSpec listener,
                        DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("observer-header", observer.source().getFileName().toString())).append('\n');
        out.append(docs.file(observer.header(), observer.description())).append('\n');
        String guard = macro(observer.header());
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        String listenerInclude = quotedRelative(observer.source().getParent(),
                listener.source().getParent().resolve(listener.header()));
        appendIncludes(out, List.of("<stdbool.h>", "<stdint.h>", listenerInclude), observer.includes());
        out.append(user.render("observer.header.preamble", "")).append('\n');

        String capacityMacro = observer.name().toUpperCase() + "_CAPACITY";
        out.append(CGenTag.generatedItem("macro", capacityMacro)).append('\n');
        out.append("#define ").append(capacityMacro).append(" ").append(observer.capacity()).append("u\n\n");

        out.append(CGenTag.generatedItem("context", observer.name())).append('\n');
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

        out.append(CGenTag.generatedItem("function", "init")).append('\n');
        out.append("void ").append(observer.name()).append("_init(").append(observer.name()).append("_context_t *context);\n\n");

        out.append(CGenTag.generatedItem("function", "subscribe")).append('\n');
        out.append("bool ").append(observer.name()).append("_subscribe(").append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber);\n\n");

        out.append(CGenTag.generatedItem("function", "unsubscribe")).append('\n');
        out.append("bool ").append(observer.name()).append("_unsubscribe(").append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber);\n\n");

        for (InterfaceSpec.Function function : listener.functions()) {
            out.append(CGenTag.generatedItem("function", "publish_" + function.name())).append('\n');
            out.append(docs.function(observer.name() + "_publish_" + function.name(), function.description(), "void", function.parameters()));
            out.append("void ").append(observer.name()).append("_publish_").append(function.name())
                    .append('(').append(observer.name()).append("_context_t *context");
            appendParameters(out, function.parameters(), true);
            out.append(");\n\n");
        }
        out.append(user.render("observer.header.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
        return out.toString();
    }

    String renderSource(ProjectConfig project, ObserverSpec observer, InterfaceSpec listener,
                        DocumentationRenderer docs, UserRegions user) {
        StringBuilder out = new StringBuilder();
        out.append(CGenTag.generatedFile("observer-source", observer.source().getFileName().toString())).append('\n');
        out.append(docs.file(observer.sourceFile(), observer.description())).append('\n');
        out.append("#include \"").append(observer.header()).append("\"\n");
        out.append("#include <stddef.h>\n\n");
        out.append(user.render("observer.source.includes", "")).append('\n');

        String capacityMacro = observer.name().toUpperCase() + "_CAPACITY";

        out.append(CGenTag.generatedItem("function", "init")).append('\n');
        out.append("void ").append(observer.name()).append("_init(").append(observer.name()).append("_context_t *context)\n{\n")
                .append(indent(project, 1)).append("context->count = 0U;\n")
                .append("}\n\n");

        out.append(CGenTag.generatedItem("function", "subscribe")).append('\n');
        out.append("bool ").append(observer.name()).append("_subscribe(").append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber)\n{\n");
        out.append(indent(project, 1)).append("bool cgen_result = false;\n\n");
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
        out.append(indent(project, 3)).append("cgen_result = true;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n\n");
        out.append(indent(project, 1)).append("return cgen_result;\n");
        out.append("}\n\n");

        out.append(CGenTag.generatedItem("function", "unsubscribe")).append('\n');
        out.append("bool ").append(observer.name()).append("_unsubscribe(").append(observer.name())
                .append("_context_t *context, const ").append(listener.name()).append("_interface_t *subscriber)\n{\n");
        out.append(indent(project, 1)).append("bool cgen_result = false;\n");
        out.append(indent(project, 1)).append("uint32_t index;\n\n");
        out.append(indent(project, 1)).append("for (index = 0U; index < context->count; index++)\n");
        out.append(indent(project, 1)).append("{\n");
        out.append(indent(project, 2)).append("if (context->subscribers[index] == subscriber)\n");
        out.append(indent(project, 2)).append("{\n");
        out.append(indent(project, 3)).append("context->count--;\n");
        out.append(indent(project, 3)).append("context->subscribers[index] = context->subscribers[context->count];\n");
        out.append(indent(project, 3)).append("cgen_result = true;\n");
        out.append(indent(project, 3)).append("break;\n");
        out.append(indent(project, 2)).append("}\n");
        out.append(indent(project, 1)).append("}\n\n");
        out.append(indent(project, 1)).append("return cgen_result;\n");
        out.append("}\n\n");

        for (InterfaceSpec.Function function : listener.functions()) {
            out.append(CGenTag.generatedItem("function", "publish_" + function.name())).append('\n');
            out.append("void ").append(observer.name()).append("_publish_").append(function.name())
                    .append('(').append(observer.name()).append("_context_t *context");
            appendParameters(out, function.parameters(), true);
            out.append(")\n{\n");
            out.append(indent(project, 1)).append("uint32_t index;\n\n");
            out.append(indent(project, 1)).append("for (index = 0U; index < context->count; index++)\n");
            out.append(indent(project, 1)).append("{\n");
            out.append(indent(project, 2)).append(listener.name()).append('_').append(function.name())
                    .append("(context->subscribers[index]");
            for (InterfaceSpec.Parameter parameter : function.parameters()) {
                out.append(", ").append(parameter.name());
            }
            out.append(");\n");
            out.append(indent(project, 1)).append("}\n");
            out.append("}\n\n");
        }
        out.append(user.render("observer.source.footer", ""));
        out.append(user.renderOrphans());
        return out.toString();
    }
}
