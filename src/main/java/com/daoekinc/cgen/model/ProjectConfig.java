package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public record ProjectConfig(
        Path root,
        String name,
        String version,
        Documentation documentation,
        int indent,
        String lineEnding,
        String publicVariableStyle,
        boolean suppressUnusedWarnings,
        boolean camelCaseFunctions,
        StateSmithSettings stateSmith) {

    public record Documentation(String style, Path customFile) {
    }

    /**
     * {@code stateSmith:} project settings, only needed by {@code engine: statesmith} state
     * machines. {@code command} defaults to {@code ss.cli} when the whole block is omitted;
     * {@code version} has no default and is only required (checked at generate time, not here)
     * when the project actually contains a statesmith-engine machine.
     */
    public record StateSmithSettings(String command, String version) {
    }

    public static ProjectConfig from(Path file, Map<String, Object> yaml) {
        String context = file.toString();
        Values.onlyKeys(yaml, context, "schema", "name", "version", "documentation", "format", "stateSmith");
        int schema = Values.optionalInt(yaml, "schema", 1, context);
        if (schema != 1) {
            throw new CGenException(context + " uses unsupported schema " + schema);
        }
        Path root;
        try {
            // Canonicalize once here so every downstream comparison against project.root()
            // (ProjectService.existingDirectory/safeDirectory, the interface-vs-scope filter
            // in CGenerator.generate()) uses the same representation - on Windows, a raw
            // toAbsolutePath().normalize() can retain an 8.3 short name (e.g. RUNNER~1 on
            // GitHub Actions runners) that no longer string-prefix-matches a realpath'd scope.
            root = file.toAbsolutePath().normalize().getParent().toRealPath();
        } catch (IOException exception) {
            throw new CGenException("Cannot resolve project root for " + file + ": " + exception.getMessage(), exception);
        }
        String name = Values.requiredString(yaml, "name", context);
        String version = Values.requiredString(yaml, "version", context);

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
        Values.onlyKeys(format, context + ".format", "indent", "lineEnding", "publicVariables", "suppressUnusedWarnings",
                "functionNaming");
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
        String publicVariableStyle = Values.optionalString(format, "publicVariables", "extern", context + ".format").toLowerCase();
        if (!publicVariableStyle.equals("extern") && !publicVariableStyle.equals("accessors")) {
            throw new CGenException("format.publicVariables must be extern or accessors");
        }

        boolean suppressUnusedWarnings = Boolean.parseBoolean(
                Values.optionalString(format, "suppressUnusedWarnings", "true", context + ".format"));

        String functionNaming = Values.optionalString(format, "functionNaming", "snake_case", context + ".format");
        if (!functionNaming.equals("snake_case") && !functionNaming.equals("camelCase")) {
            throw new CGenException("format.functionNaming must be snake_case or camelCase");
        }
        boolean camelCaseFunctions = functionNaming.equals("camelCase");

        Map<String, Object> stateSmithMap = Values.optionalMap(yaml, "stateSmith", context);
        Values.onlyKeys(stateSmithMap, context + ".stateSmith", "command", "version");
        String stateSmithCommand = Values.optionalString(stateSmithMap, "command", "ss.cli", context + ".stateSmith");
        String stateSmithVersion = Values.optionalString(stateSmithMap, "version", null, context + ".stateSmith");
        StateSmithSettings stateSmith = new StateSmithSettings(stateSmithCommand, stateSmithVersion);

        return new ProjectConfig(root, name, version,
                new Documentation(style, customFile), indent, lineEnding, publicVariableStyle, suppressUnusedWarnings,
                camelCaseFunctions, stateSmith);
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
