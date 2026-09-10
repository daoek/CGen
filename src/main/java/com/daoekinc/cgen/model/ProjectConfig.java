package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.Map;

public record ProjectConfig(
        Path root,
        String name,
        String version,
        Documentation documentation,
        int indent,
        String lineEnding) {

    public record Documentation(String style, Path customFile) {
    }

    public static ProjectConfig from(Path file, Map<String, Object> yaml) {
        String context = file.toString();
        Values.onlyKeys(yaml, context, "schema", "name", "version", "paths", "documentation", "format");
        int schema = Values.optionalInt(yaml, "schema", 1, context);
        if (schema != 1) {
            throw new CGenException(context + " uses unsupported schema " + schema);
        }
        Path root = file.toAbsolutePath().normalize().getParent();
        String name = Values.requiredString(yaml, "name", context);
        String version = Values.requiredString(yaml, "version", context);

        // Accepted for compatibility with early project files; placement is now command-scoped.
        Map<String, Object> paths = Values.optionalMap(yaml, "paths", context);
        Values.onlyKeys(paths, context + ".paths", "interfaces", "modules");

        Map<String, Object> docs = Values.optionalMap(yaml, "documentation", context);
        Values.onlyKeys(docs, context + ".documentation", "style", "file");
        String style = Values.optionalString(docs, "style", "doxygen", context + ".documentation").toLowerCase();
        if (!style.equals("doxygen") && !style.equals("none") && !style.equals("custom")) {
            throw new CGenException("documentation.style must be doxygen, none, or custom");
        }
        String customName = Values.optionalString(docs, "file", null, context + ".documentation");
        Path customFile = customName == null ? null : resolveInside(root, customName, "documentation.file");
        if (style.equals("custom") && customFile == null) {
            throw new CGenException("documentation.file is required when documentation.style is custom");
        }

        Map<String, Object> format = Values.optionalMap(yaml, "format", context);
        Values.onlyKeys(format, context + ".format", "indent", "lineEnding");
        int indent = Values.optionalInt(format, "indent", 4, context + ".format");
        if (indent < 2 || indent > 8) {
            throw new CGenException("format.indent must be between 2 and 8");
        }
        String lineEndingName = Values.optionalString(format, "lineEnding", "lf", context + ".format").toLowerCase();
        String lineEnding = switch (lineEndingName) {
            case "lf" -> "\n";
            case "crlf" -> "\r\n";
            default -> throw new CGenException("format.lineEnding must be lf or crlf");
        };

        return new ProjectConfig(root, name, version,
                new Documentation(style, customFile), indent, lineEnding);
    }

    private static Path resolveInside(Path root, String configured, String label) {
        Path relative;
        try {
            relative = Path.of(configured);
        } catch (RuntimeException exception) {
            throw new CGenException(label + " is not a valid path", exception);
        }
        if (relative.isAbsolute()) {
            throw new CGenException(label + " must be relative to the project");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new CGenException(label + " must stay inside the project");
        }
        return resolved;
    }
}
