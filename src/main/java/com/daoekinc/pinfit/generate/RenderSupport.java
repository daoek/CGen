package com.daoekinc.pinfit.generate;

import com.daoekinc.pinfit.model.InterfaceSpec;
import com.daoekinc.pinfit.model.ProjectConfig;
import com.daoekinc.pinfit.tag.PinfitTag;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RenderSupport {
    private RenderSupport() {
    }

    @SafeVarargs
    static void appendIncludes(StringBuilder out, List<String>... groups) {
        Set<String> includes = new LinkedHashSet<>();
        for (List<String> group : groups) {
            includes.addAll(group);
        }
        for (String include : includes) {
            out.append("#include ").append(include).append('\n');
        }
        if (!includes.isEmpty()) {
            out.append('\n');
        }
    }

    static void appendParameters(StringBuilder out, List<InterfaceSpec.Parameter> parameters, boolean leadingComma) {
        boolean comma = leadingComma;
        for (InterfaceSpec.Parameter parameter : parameters) {
            if (comma) {
                out.append(", ");
            }
            appendTypedName(out, parameter.type(), parameter.name());
            comma = true;
        }
    }

    static void appendEnums(StringBuilder out, ProjectConfig project, List<InterfaceSpec.EnumDef> enums, DocumentationRenderer docs) {
        for (InterfaceSpec.EnumDef type : enums) {
            out.append(PinfitTag.generatedItem("enum", type.name())).append('\n');
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

    static void appendTypedName(StringBuilder out, String type, String name) {
        out.append(type);
        if (!type.stripTrailing().endsWith("*")) {
            out.append(' ');
        }
        out.append(name);
    }

    static String indent(ProjectConfig project, int level) {
        return " ".repeat(project.indent() * level);
    }

    static void appendUnusedSilencer(StringBuilder out, ProjectConfig project, int level, String name) {
        if (project.suppressUnusedWarnings()) {
            out.append(indent(project, level)).append("(void)").append(name).append(";\n");
        }
    }

    /**
     * Joins name segments into one generated C function identifier, honoring
     * {@code format.functionNaming}. Each segment may itself contain underscores (a
     * user-supplied identifier or a multi-word literal like "go_to_state"); under
     * camelCase those are treated as word boundaries too, so "motor_driver" + "go_to_state"
     * becomes "motorDriverGoToState". snake_case reproduces today's plain "_"-join verbatim.
     */
    static String functionName(ProjectConfig project, String... segments) {
        if (!project.camelCaseFunctions()) {
            return String.join("_", segments);
        }
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (String segment : segments) {
            for (String word : segment.split("_")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (first) {
                    result.append(Character.toLowerCase(word.charAt(0))).append(word.substring(1));
                    first = false;
                } else {
                    result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
        }
        return result.toString();
    }

    static String macro(String file) {
        String value = file.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return value.endsWith("_") ? value : value + "_";
    }

    static String quotedRelative(Path fromDirectory, Path file) {
        return "\"" + fromDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/') + "\"";
    }
}
