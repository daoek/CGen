package com.daoekinc.pinfit.generate;

import static com.daoekinc.pinfit.generate.RenderSupport.appendIncludes;
import static com.daoekinc.pinfit.generate.RenderSupport.indent;
import static com.daoekinc.pinfit.generate.RenderSupport.macro;

import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.model.StatusCodesSpec;
import com.daoekinc.pinfit.tag.PinfitTag;
import com.daoekinc.pinfit.tag.TagHelper.UserRegions;

final class StatusCodesRenderer {
    String render(ProjectConfig project, StatusCodesSpec spec, DocumentationRenderer docs, UserRegions user) {
        String guard = macro(spec.header());

        StringBuilder out = new StringBuilder();
        appendTop(out, spec, docs, user, guard);
        appendEnum(out, project, spec, docs);
        appendMacros(out, project, spec);
        appendBottom(out, user, guard);
        return out.toString();
    }

    private static void appendTop(StringBuilder out, StatusCodesSpec spec, DocumentationRenderer docs, UserRegions user, String guard) {
        out.append(PinfitTag.generatedFile("status-codes", spec.source().getFileName().toString())).append('\n');
        out.append(docs.file(spec.header(), spec.description())).append('\n');
        out.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
        appendIncludes(out, spec.includes());
        out.append(user.render("status-codes.preamble", "")).append('\n');
    }

    private static void appendEnum(StringBuilder out, ProjectConfig project, StatusCodesSpec spec, DocumentationRenderer docs) {
        out.append(PinfitTag.generatedItem("enum", spec.name())).append('\n');
        out.append(docs.type(spec.name() + "_t", spec.description()));
        out.append("typedef enum\n{\n");
        for (int index = 0; index < spec.codes().size(); index++) {
            StatusCodesSpec.Code code = spec.codes().get(index);
            out.append(indent(project, 1)).append(constant(spec, code.name())).append(" = ").append(code.value());
            if (index + 1 < spec.codes().size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("} ").append(spec.name()).append("_t;\n\n");
    }

    private static void appendMacros(StringBuilder out, ProjectConfig project, StatusCodesSpec spec) {
        String prefix = spec.name().toUpperCase();
        out.append(PinfitTag.generatedItem("macro", prefix + "_SUCCEEDED")).append('\n');
        out.append("#define ").append(prefix).append("_SUCCEEDED(status) ((status) == ")
                .append(constant(spec, spec.successCode())).append(")\n\n");
        out.append(PinfitTag.generatedItem("macro", prefix + "_FAILED")).append('\n');
        out.append("#define ").append(prefix).append("_FAILED(status) (!").append(prefix).append("_SUCCEEDED(status))\n\n");
        out.append(PinfitTag.generatedItem("macro", prefix + "_CHECK")).append('\n');
        out.append("#define ").append(prefix).append("_CHECK(status_expression) \\\n")
                .append(indent(project, 1)).append("do { ").append(spec.name()).append("_t pinfit_status = (status_expression); \\\n")
                .append(indent(project, 2)).append("if (").append(prefix).append("_FAILED(pinfit_status)) { return pinfit_status; } \\\n")
                .append(indent(project, 1)).append("} while (0)\n\n");
    }

    private static void appendBottom(StringBuilder out, UserRegions user, String guard) {
        out.append(user.render("status-codes.footer", ""));
        out.append(user.renderOrphans());
        out.append("\n#endif /* ").append(guard).append(" */\n");
    }

    private static String constant(StatusCodesSpec spec, String codeName) {
        return (spec.name() + "_" + codeName).toUpperCase();
    }
}
