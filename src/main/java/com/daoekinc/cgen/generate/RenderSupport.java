package com.daoekinc.cgen.generate;

import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
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

    static String macro(String file) {
        String value = file.toUpperCase().replaceAll("[^A-Z0-9]", "_");
        return value.endsWith("_") ? value : value + "_";
    }

    static String quotedRelative(Path fromDirectory, Path file) {
        return "\"" + fromDirectory.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/') + "\"";
    }
}
