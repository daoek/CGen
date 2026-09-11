package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class Values {
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Values() {
    }

    static void onlyKeys(Map<String, Object> map, String context, String... allowed) {
        Set<String> keys = Set.of(allowed);
        for (String key : map.keySet()) {
            if (!keys.contains(key)) {
                throw new CGenException(context + " contains unknown key '" + key + "'");
            }
        }
    }

    static String requiredString(Map<String, Object> map, String key, String context) {
        String value = optionalString(map, key, null, context);
        if (value == null || value.isBlank()) {
            throw new CGenException(context + "." + key + " is required");
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
            throw new CGenException(context + "." + key + " must be text");
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
            throw new CGenException(context + "." + key + " must be an integer");
        }
    }

    static Map<String, Object> optionalMap(Map<String, Object> map, String key, String context) {
        Object value = map.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new CGenException(context + "." + key + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String text)) {
                throw new CGenException(context + "." + key + " contains a non-text key");
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
                throw new CGenException(context + "." + key + " must contain non-empty one-line strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
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
                    throw new CGenException(message, "Example YAML", example);
                }
                throw new CGenException(message);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String text)) {
                    throw new CGenException(context + "." + key + "[" + index + "] contains a non-text key");
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
                        throw new CGenException(itemContext + " contains a non-text key");
                    }
                    item.put(text, entry.getValue());
                }
                result.add(item);
            } else {
                String message = itemContext + " must be a mapping or a \"type name\" string";
                if (example != null) {
                    throw new CGenException(message, "Example YAML", example);
                }
                throw new CGenException(message);
            }
            index++;
        }
        return result;
    }

    static Map<String, Object> compactField(String text, String context) {
        String trimmed = text.strip();
        int splitIndex = -1;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                splitIndex = i;
                break;
            }
        }
        if (splitIndex < 0 || splitIndex == trimmed.length() - 1) {
            throw new CGenException(context + " must be \"type name\", got '" + text + "'");
        }
        String name = trimmed.substring(splitIndex + 1);
        String type = trimmed.substring(0, splitIndex + 1).strip();
        if (type.isBlank() || !C_IDENTIFIER.matcher(name).matches()) {
            throw new CGenException(context + " must be \"type name\", got '" + text + "'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("name", name);
        return result;
    }

    static Map<String, Object> compactVariable(String text, String context) {
        List<String> tokens = new ArrayList<>(List.of(text.strip().split("\\s+")));
        boolean isPublic = !tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("public");
        if (isPublic) {
            tokens.remove(tokens.size() - 1);
        }
        Map<String, Object> field = compactField(String.join(" ", tokens), context);
        if (isPublic) {
            field.put("visibility", "public");
        }
        return field;
    }

    static String identifier(String value, String context) {
        if (!C_IDENTIFIER.matcher(value).matches()) {
            throw new CGenException(context + " must be a valid C identifier, got '" + value + "'");
        }
        return value;
    }

    static void uniqueNames(List<String> names, String context) {
        Set<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (!unique.add(name)) {
                throw new CGenException(context + " contains duplicate name '" + name + "'");
            }
        }
    }

    static String outputFile(String value, String suffix, String context) {
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..") || !value.endsWith(suffix)) {
            throw new CGenException(context + " must be a local " + suffix + " file name");
        }
        return value;
    }
}
