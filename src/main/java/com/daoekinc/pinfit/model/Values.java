package com.daoekinc.pinfit.model;

import com.daoekinc.pinfit.PinfitException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Values {
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String ARRAY_BOUND = "(?:\\[(?:[0-9]+|[A-Za-z_][A-Za-z0-9_]*)?])+";
    private static final Pattern ARRAY_DECLARATOR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*" + ARRAY_BOUND);
    private static final Pattern ARRAY_SUFFIX = Pattern.compile(ARRAY_BOUND + "$");

    private Values() {
    }

    static void onlyKeys(Map<String, Object> map, String context, String... allowed) {
        Set<String> keys = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!keys.contains(key)) {
                throw new PinfitException(context + " contains unknown key '" + key + "'");
            }
        }
    }

    static String requiredString(Map<String, Object> map, String key, String context) {
        String value = optionalString(map, key, null, context);
        if (value == null || value.isBlank()) {
            throw new PinfitException(context + "." + key + " is required");
        }
        return value;
    }

    static String optionalString(Map<String, Object> map, String key, String fallback, String context) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            if (value instanceof Number || value instanceof Boolean) {
                return value.toString();
            }
            throw new PinfitException(context + "." + key + " must be text");
        }
        return text;
    }

    static int optionalInt(Map<String, Object> map, String key, int fallback, String context) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new PinfitException(context + "." + key + " must be an integer");
        }
    }

    static Map<String, Object> optionalMap(Map<String, Object> map, String key, String context) {
        Object value = map.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new PinfitException(context + "." + key + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String text)) {
                throw new PinfitException(context + "." + key + " contains a non-text key");
            }
            result.put(text, entry.getValue());
        }
        return result;
    }

    static List<Object> optionalList(Map<String, Object> map, String key, String context) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            return List.of(value);
        }
        return new ArrayList<>(list);
    }

    static List<String> stringList(Map<String, Object> map, String key, String context) {
        List<String> result = new ArrayList<>();
        for (Object value : optionalList(map, key, context)) {
            if (!(value instanceof String text) || text.isBlank() || text.contains("\n") || text.contains("\r")) {
                throw new PinfitException(context + "." + key + " must contain non-empty one-line strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    // A plain YAML double-quoted scalar like "osal.h" is consumed by the YAML parser -
    // the string value is just osal.h, with no quote characters left to emit into
    // "#include ...". Catch that here instead of silently generating invalid C.
    static List<String> includeList(Map<String, Object> map, String key, String context) {
        List<String> includes = stringList(map, key, context);
        for (String include : includes) {
            boolean angled = include.length() >= 2 && include.startsWith("<") && include.endsWith(">");
            boolean quoted = include.length() >= 2 && include.startsWith("\"") && include.endsWith("\"");
            if (!angled && !quoted) {
                throw new PinfitException(context + "." + key + " entry '" + include
                        + "' must be wrapped in <...> or \"...\"; a YAML double-quoted string like \"osal.h\" "
                        + "loses its quotes, so single-quote it in YAML: '\"osal.h\"'",
                        "Example YAML", key + ":\n  - <stdint.h>\n  - '\"osal.h\"'");
            }
        }
        return includes;
    }

    static List<Map<String, Object>> mapList(Map<String, Object> map, String key, String context) {
        return mapList(map, key, context, null);
    }

    static List<Map<String, Object>> mapList(Map<String, Object> map, String key, String context, String example) {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Object value : optionalList(map, key, context)) {
            if (!(value instanceof Map<?, ?> raw)) {
                String message = context + "." + key + "[" + index + "] must be a mapping";
                if (example != null) {
                    throw new PinfitException(message, "Example YAML", example);
                }
                throw new PinfitException(message);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String text)) {
                    throw new PinfitException(context + "." + key + "[" + index + "] contains a non-text key");
                }
                item.put(text, entry.getValue());
            }
            result.add(item);
            index++;
        }
        return result;
    }

    static List<Map<String, Object>> itemList(Map<String, Object> map, String key, String context, String example,
                                              Function<String, Map<String, Object>> compact) {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (Object value : optionalList(map, key, context)) {
            String itemContext = context + "." + key + "[" + index + "]";
            if (value instanceof String text) {
                result.add(compact.apply(text));
            } else if (value instanceof Map<?, ?> raw) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (!(entry.getKey() instanceof String text)) {
                        throw new PinfitException(itemContext + " contains a non-text key");
                    }
                    item.put(text, entry.getValue());
                }
                result.add(item);
            } else {
                String message = itemContext + " must be a mapping or a \"type name\" string";
                if (example != null) {
                    throw new PinfitException(message, "Example YAML", example);
                }
                throw new PinfitException(message);
            }
            index++;
        }
        return result;
    }

    // A mapping of C type name to a one-line C expression, e.g.
    //   invalidReturns:
    //     flash_command_t: FLASH_COMMAND_NONE
    static Map<String, String> stringMap(Map<String, Object> map, String key, String context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : optionalMap(map, key, context).entrySet()) {
            String entryContext = context + "." + key + "." + entry.getKey();
            if (entry.getKey().isBlank()) {
                throw new PinfitException(context + "." + key + " contains a blank key");
            }
            Map<String, Object> holder = new LinkedHashMap<>();
            holder.put("value", entry.getValue());
            String value = optionalString(holder, "value", null, entryContext);
            if (value == null || value.isBlank()) {
                throw new PinfitException(entryContext + " must be a non-empty one-line C expression");
            }
            result.put(entry.getKey().strip(), InterfaceSpec.oneLineExpression(value, entryContext));
        }
        return Map.copyOf(result);
    }

    record CommonFields(String name, String description, String header, String sourceFile,
                        List<String> includes, List<InterfaceSpec.Field> context) {
    }

    static CommonFields commonFields(Map<String, Object> yaml, String contextName, String kindLabel) {
        String name = identifier(requiredString(yaml, "name", contextName), contextName + ".name");
        String description = optionalString(yaml, "description", name + " " + kindLabel, contextName);
        String header = outputFile(optionalString(yaml, "header", name + ".h", contextName), ".h", contextName + ".header");
        String sourceFile = outputFile(optionalString(yaml, "source", name + ".c", contextName), ".c", contextName + ".source");
        List<String> includes = includeList(yaml, "includes", contextName);
        List<InterfaceSpec.Field> context = InterfaceSpec.parseFields(yaml, "context", contextName);
        return new CommonFields(name, description, header, sourceFile, includes, context);
    }

    static Map<String, Object> compactField(String text, String context) {
        String trimmed = text.strip();
        Matcher arraySuffixMatch = ARRAY_SUFFIX.matcher(trimmed);
        String arraySuffix = arraySuffixMatch.find() ? trimmed.substring(arraySuffixMatch.start()) : "";
        String withoutArray = arraySuffix.isEmpty() ? trimmed : trimmed.substring(0, arraySuffixMatch.start());
        int splitIndex = -1;
        for (int i = withoutArray.length() - 1; i >= 0; i--) {
            char c = withoutArray.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                splitIndex = i;
                break;
            }
        }
        if (splitIndex < 0 || splitIndex == withoutArray.length() - 1) {
            throw new PinfitException(context + " must be \"type name\", got '" + text + "'");
        }
        String baseName = withoutArray.substring(splitIndex + 1);
        String type = withoutArray.substring(0, splitIndex + 1).strip();
        if (type.isBlank() || !C_IDENTIFIER.matcher(baseName).matches()) {
            throw new PinfitException(context + " must be \"type name\", got '" + text + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("name", baseName + arraySuffix);
        return result;
    }

    private static final Set<String> COMPACT_VARIABLE_VISIBILITIES = Set.of("public", "get", "set");

    static Map<String, Object> compactVariable(String text, String context) {
        List<String> tokens = new ArrayList<>(List.of(text.strip().split("\\s+")));
        String visibility = null;
        if (!tokens.isEmpty() && COMPACT_VARIABLE_VISIBILITIES.contains(tokens.get(tokens.size() - 1))) {
            visibility = tokens.remove(tokens.size() - 1);
        }
        Map<String, Object> field = compactField(String.join(" ", tokens), context);
        if (visibility != null) {
            field.put("visibility", visibility);
        }
        return field;
    }

    static String identifier(String value, String context) {
        if (!C_IDENTIFIER.matcher(value).matches()) {
            throw new PinfitException(context + " must be a valid C identifier, got '" + value + "'");
        }
        return value;
    }

    static String variableDeclaratorName(String value, String context) {
        if (!C_IDENTIFIER.matcher(value).matches() && !ARRAY_DECLARATOR.matcher(value).matches()) {
            throw new PinfitException(context + " must be a valid C identifier, optionally with array brackets"
                    + " (e.g. 'buffer[6]'), got '" + value + "'");
        }
        return value;
    }

    static void uniqueNames(List<String> names, String context) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (!unique.add(name)) {
                throw new PinfitException(context + " contains duplicate name '" + name + "'");
            }
        }
    }

    static String outputFile(String value, String suffix, String context) {
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..") || !value.endsWith(suffix)) {
            throw new PinfitException(context + " must be a local " + suffix + " file name");
        }
        return value;
    }
}
