package com.daoekinc.cgen.generate;

import com.daoekinc.cgen.CGenException;
import com.daoekinc.cgen.config.YamlFiles;
import com.daoekinc.cgen.model.InterfaceSpec;
import com.daoekinc.cgen.model.ProjectConfig;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DocumentationRenderer {
    private final String style;
    private final Map<String, String> templates;

    DocumentationRenderer(ProjectConfig project, YamlFiles yamlFiles) {
        style = project.documentation().style();
        if (!style.equals("custom")) {
            templates = Map.of();
            return;
        }
        if (!Files.isRegularFile(project.documentation().customFile())) {
            throw new CGenException("Custom documentation file not found: " + project.documentation().customFile());
        }
        Map<String, Object> yaml = yamlFiles.load(project.documentation().customFile());
        for (String key : yaml.keySet()) {
            if (!key.equals("file") && !key.equals("function") && !key.equals("type") && !key.equals("variable")) {
                throw new CGenException("Custom documentation contains unknown key '" + key + "'");
            }
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        yaml.forEach((key, value) -> {
            if (!(value instanceof String text)) {
                throw new CGenException("Custom documentation template '" + key + "' must be text");
            }
            loaded.put(key, text.stripTrailing());
        });
        templates = Map.copyOf(loaded);
    }

    String file(String file, String brief) {
        if (style.equals("none")) {
            return "";
        }
        if (style.equals("custom")) {
            return custom("file", Map.of("file", file, "brief", brief, "name", file));
        }
        return "/**\n * @file " + file + "\n * @brief " + brief + "\n */\n";
    }

    String function(String name, String brief, String returnType, List<InterfaceSpec.Parameter> parameters) {
        if (style.equals("none")) {
            return "";
        }
        String parameterSummary = parameters.stream()
                .map(parameter -> parameter.name() + ": " + parameter.description())
                .reduce((left, right) -> left + ", " + right).orElse("");
        if (style.equals("custom")) {
            return custom("function", Map.of("name", name, "brief", brief, "return", returnType, "params", parameterSummary));
        }
        StringBuilder result = new StringBuilder("/**\n * @brief ").append(brief).append('\n');
        for (InterfaceSpec.Parameter parameter : parameters) {
            result.append(" * @param ").append(parameter.name()).append(' ')
                    .append(parameter.description().isBlank() ? parameter.name() : parameter.description()).append('\n');
        }
        if (!returnType.equals("void")) {
            result.append(" * @return ").append(returnType).append(" result.\n");
        }
        return result.append(" */\n").toString();
    }

    String type(String name, String brief) {
        return simple("type", name, brief);
    }

    String variable(String name, String brief) {
        return simple("variable", name, brief);
    }

    private String simple(String key, String name, String brief) {
        if (style.equals("none")) {
            return "";
        }
        if (style.equals("custom")) {
            return custom(key, Map.of("name", name, "brief", brief));
        }
        return "/** @brief " + (brief.isBlank() ? name : brief) + " */\n";
    }

    private String custom(String key, Map<String, String> variables) {
        String template = templates.getOrDefault(key, "");
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            template = template.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return template.isBlank() ? "" : template + "\n";
    }
}
