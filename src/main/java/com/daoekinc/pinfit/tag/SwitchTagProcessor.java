package com.daoekinc.pinfit.tag;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a hand-written {@code @PinfitSwitch} comment marker, placed by the user directly above
 * their own {@code switch (...) { ... }} statement inside any usercode region, into a full case
 * list for the named enum - one case per member, each with its own nested usercode region so the
 * case body survives regeneration. See the "@PinfitSwitch" section of the README.
 */
public final class SwitchTagProcessor {
    // Accepts the legacy "@CGenSwitch" spelling alongside the current "@PinfitSwitch" - this tag
    // lives in the user's own hand-written code and is never rewritten by Pinfit, so both forms
    // must keep working forever, not just until the next regenerate.
    private static final Pattern TAG = Pattern.compile("^\\s*/\\*@(?:Pinfit|CGen)Switch\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\*/\\s*$");
    private static final Pattern SWITCH_OPEN = Pattern.compile("^(\\s*)switch\\s*\\((.*)\\)\\s*(\\{)?\\s*$");
    private static final Pattern CASE_LABEL = Pattern.compile("^\\s*case\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*\\{?\\s*$");
    private static final Pattern DEFAULT_LABEL = Pattern.compile("^\\s*default\\s*:\\s*\\{?\\s*$");

    private SwitchTagProcessor() {
    }

    public static boolean isUsed(String content) {
        return content.contains("@PinfitSwitch") || content.contains("@CGenSwitch");
    }

    public static String process(String content, Path file, int indentUnit, Function<String, List<String>> enumResolver) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher tagMatch = TAG.matcher(line);
            if (!tagMatch.matches()) {
                result.add(line);
                i++;
                continue;
            }
            String enumType = tagMatch.group(1);
            result.add(line);
            i++;
            while (i < lines.size() && lines.get(i).isBlank()) {
                result.add(lines.get(i));
                i++;
            }
            if (i >= lines.size()) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType + " must be followed by a switch statement");
            }
            Matcher switchMatch = SWITCH_OPEN.matcher(lines.get(i));
            if (!switchMatch.matches()) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType + " must be immediately followed by a "
                        + "'switch (...)' statement, found: '" + lines.get(i).strip() + "'");
            }
            String switchIndent = switchMatch.group(1);
            String switchExpr = switchMatch.group(2);
            boolean braceOnSameLine = switchMatch.group(3) != null;
            i++;
            if (!braceOnSameLine) {
                while (i < lines.size() && lines.get(i).isBlank()) {
                    i++;
                }
                if (i >= lines.size() || !lines.get(i).strip().equals("{")) {
                    throw new PinfitException(file + ": @PinfitSwitch " + enumType + " switch statement must open with '{'");
                }
                i++;
            }
            int bodyStart = i;
            int depth = 1;
            while (i < lines.size() && depth > 0) {
                for (char c : lines.get(i).toCharArray()) {
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                if (depth == 0) {
                    break;
                }
                i++;
            }
            if (depth != 0) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType + " switch statement is missing its closing brace");
            }
            if (!lines.get(i).strip().equals("}")) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType
                        + " switch statement's closing '}' must be alone on its own line");
            }
            List<String> oldBody = lines.subList(bodyStart, i);
            i++;

            List<String> members = enumResolver.apply(enumType);
            if (members.isEmpty()) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType + " resolved to an enum with no members");
            }
            Map<String, String> caseBodies = resolveCaseBodies(oldBody, enumType, members, file);

            String unit = " ".repeat(Math.max(indentUnit, 1));
            String caseIndent = switchIndent + unit;
            String bodyIndent = caseIndent + unit;
            result.add(switchIndent + "switch (" + switchExpr + ")");
            result.add(switchIndent + "{");
            for (String member : members) {
                result.add(caseIndent + "case " + member + ":");
                result.add(caseIndent + "{");
                appendUserRegion(result, "switchcase." + enumType + "." + member, caseBodies.get(member), bodyIndent);
                result.add(bodyIndent + "break;");
                result.add(caseIndent + "}");
                result.add("");
            }
            result.add(caseIndent + "default:");
            result.add(caseIndent + "{");
            appendUserRegion(result, "switchcase." + enumType + ".default", caseBodies.get("default"), bodyIndent);
            result.add(bodyIndent + "break;");
            result.add(caseIndent + "}");
            result.add(switchIndent + "}");
        }
        return String.join("\n", result);
    }

    private static Map<String, String> resolveCaseBodies(List<String> oldBody, String enumType, List<String> members, Path file) {
        Map<String, String> managed = extractManagedCases(oldBody, enumType, file);
        if (!managed.isEmpty()) {
            return managed;
        }
        Map<String, String> legacy = extractLegacyCases(oldBody);
        Set<String> memberSet = new LinkedHashSet<>(members);
        for (String label : legacy.keySet()) {
            if (!label.equals("default") && !memberSet.contains(label)) {
                throw new PinfitException(file + ": @PinfitSwitch " + enumType + " has an existing 'case " + label
                        + ":' that is not a member of " + enumType + " - fix or remove it before Pinfit can adopt this switch");
            }
        }
        return legacy;
    }

    private static Map<String, String> extractManagedCases(List<String> oldBody, String enumType, Path file) {
        Map<String, String> regions = new LinkedHashMap<>();
        String prefix = "switchcase." + enumType + ".";
        String current = null;
        List<String> body = null;
        for (String line : oldBody) {
            String beginName = PinfitTag.userBeginName(line);
            if (beginName != null) {
                if (current != null) {
                    throw new PinfitException(file + ": nested user region inside @PinfitSwitch " + enumType);
                }
                current = beginName;
                body = new ArrayList<>();
            } else if (PinfitTag.isUserEnd(line)) {
                if (current == null) {
                    throw new PinfitException(file + ": unexpected end of user region inside @PinfitSwitch " + enumType);
                }
                if (current.startsWith(prefix)) {
                    regions.put(current.substring(prefix.length()), String.join("\n", body));
                }
                current = null;
                body = null;
            } else if (current != null) {
                body.add(line);
            }
        }
        if (current != null) {
            throw new PinfitException(file + ": unclosed user region inside @PinfitSwitch " + enumType);
        }
        return regions;
    }

    private static Map<String, String> extractLegacyCases(List<String> oldBody) {
        Map<String, String> result = new LinkedHashMap<>();
        String currentLabel = null;
        List<String> body = null;
        for (String line : oldBody) {
            Matcher caseMatch = CASE_LABEL.matcher(line);
            boolean isDefault = DEFAULT_LABEL.matcher(line).matches();
            if (caseMatch.matches() || isDefault) {
                if (currentLabel != null) {
                    result.put(currentLabel, stripTrailingBreak(body));
                }
                currentLabel = isDefault ? "default" : caseMatch.group(1);
                body = new ArrayList<>();
            } else if (currentLabel != null) {
                body.add(line);
            }
        }
        if (currentLabel != null) {
            result.put(currentLabel, stripTrailingBreak(body));
        }
        return result;
    }

    private static String stripTrailingBreak(List<String> body) {
        List<String> trimmed = new ArrayList<>(body);
        trimEnd(trimmed);
        if (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).strip().equals("}")) {
            trimmed.remove(trimmed.size() - 1);
            trimEnd(trimmed);
        }
        if (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).strip().matches("break\\s*;")) {
            trimmed.remove(trimmed.size() - 1);
            trimEnd(trimmed);
        }
        while (!trimmed.isEmpty() && trimmed.get(0).isBlank()) {
            trimmed.remove(0);
        }
        return String.join("\n", trimmed);
    }

    private static void trimEnd(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
    }

    private static void appendUserRegion(List<String> out, String name, String body, String indent) {
        out.add(indent + PinfitTag.userBegin(name));
        if (body != null && !body.isEmpty()) {
            out.addAll(List.of(body.split("\n", -1)));
        }
        out.add(indent + PinfitTag.userEnd());
    }
}
