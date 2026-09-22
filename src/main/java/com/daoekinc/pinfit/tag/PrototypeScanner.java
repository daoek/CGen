package com.daoekinc.pinfit.tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds function definitions a user typed directly inside a usercode region of a Pinfit-generated
 * module source (.c) file - not part of the YAML spec, so Pinfit never emitted a prototype for them -
 * and proposes adding a forward declaration to the "module.source.prototypes" usercode region at
 * the top of the same file. This is a line-based heuristic (single-line signatures only), not a C
 * parser; it's meant to catch the common case and let the user review a diff before anything changes.
 */
public final class PrototypeScanner {
    private static final Set<String> NAME_BLOCKLIST = Set.of(
            "if", "for", "while", "switch", "else", "do", "return", "sizeof", "typedef",
            "struct", "union", "enum", "case", "default", "goto");
    private static final Pattern PAREN_SPLIT = Pattern.compile("^(?<head>[^(]*)\\((?<params>[^;{}]*)\\)\\s*(?<brace>\\{)?\\s*$");
    private static final Pattern NAME_AT_END = Pattern.compile("(?<prefix>.*?)(?<name>[A-Za-z_][A-Za-z0-9_]*)\\s*$");
    private static final String TARGET_REGION = "module.source.prototypes";

    private PrototypeScanner() {
    }

    public record Missing(String name, String signature) {
    }

    public record Scan(List<Missing> missing, String updatedContent, String diff) {
        public boolean isEmpty() {
            return missing.isEmpty();
        }
    }

    /** {@code content} must use "\n" line endings; {@code displayPath} is only used in the diff header. */
    public static Scan scan(String content, String displayPath) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        if (lines.stream().noneMatch(PinfitTag::isGeneratedFile)) {
            return new Scan(List.of(), content, "");
        }

        List<Missing> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (PinfitTag.userBeginName(line) != null) {
                depth++;
                continue;
            }
            if (PinfitTag.isUserEnd(line)) {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0) {
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//") || stripped.startsWith("*")
                    || stripped.startsWith("/*")) {
                continue;
            }
            Definition definition = matchDefinition(lines, i);
            if (definition == null || NAME_BLOCKLIST.contains(definition.name) || seen.contains(definition.name)) {
                continue;
            }
            if (hasPrototype(lines, definition.name)) {
                continue;
            }
            missing.add(new Missing(definition.name, definition.signature + ";"));
            seen.add(definition.name);
        }
        if (missing.isEmpty()) {
            return new Scan(List.of(), content, "");
        }

        int insertAt = findInsertionLine(lines);
        if (insertAt < 0) {
            return new Scan(List.of(), content, "");
        }

        List<String> updated = new ArrayList<>(lines);
        List<String> added = missing.stream().map(Missing::signature).toList();
        updated.addAll(insertAt, added);

        String updatedContent = String.join("\n", updated);
        String diff = buildDiff(displayPath, lines, insertAt, added);
        return new Scan(List.copyOf(missing), updatedContent, diff);
    }

    private record Definition(String name, String signature) {
    }

    private static Definition matchDefinition(List<String> lines, int index) {
        Matcher paren = PAREN_SPLIT.matcher(lines.get(index).stripTrailing());
        if (!paren.matches()) {
            return null;
        }
        boolean opensHere = paren.group("brace") != null;
        boolean opensNextLine = !opensHere && index + 1 < lines.size() && lines.get(index + 1).strip().equals("{");
        if (!opensHere && !opensNextLine) {
            return null;
        }
        Matcher nameMatch = NAME_AT_END.matcher(paren.group("head"));
        if (!nameMatch.matches() || nameMatch.group("prefix").strip().isEmpty()) {
            return null;
        }
        String name = nameMatch.group("name");
        String signature = lines.get(index).strip();
        if (opensHere) {
            signature = signature.substring(0, signature.lastIndexOf('{')).stripTrailing();
        }
        if (paren.group("params").strip().isEmpty()) {
            int open = signature.indexOf('(');
            int close = signature.indexOf(')', open);
            signature = signature.substring(0, open + 1) + "void" + signature.substring(close);
        }
        return new Definition(name, signature);
    }

    private static boolean hasPrototype(List<String> lines, String name) {
        Pattern prototype = Pattern.compile("^[^(]*\\b" + Pattern.quote(name) + "\\s*\\([^;{}]*\\)\\s*;\\s*$");
        for (String line : lines) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && prototype.matcher(stripped).matches()) {
                return true;
            }
        }
        return false;
    }

    private static int findInsertionLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (TARGET_REGION.equals(PinfitTag.userBeginName(lines.get(i)))) {
                for (int j = i + 1; j < lines.size(); j++) {
                    if (PinfitTag.isUserEnd(lines.get(j))) {
                        return j;
                    }
                }
            }
        }
        return -1;
    }

    private static String buildDiff(String displayPath, List<String> original, int insertAt, List<String> added) {
        int contextBefore = Math.max(0, insertAt - 3);
        int contextAfter = Math.min(original.size(), insertAt + 3);
        int oldStart = contextBefore + 1;
        int oldCount = contextAfter - contextBefore;
        int newCount = oldCount + added.size();

        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(displayPath).append('\n');
        out.append("+++ b/").append(displayPath).append('\n');
        out.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(oldStart).append(',').append(newCount).append(" @@\n");
        for (int i = contextBefore; i < insertAt; i++) {
            out.append(' ').append(original.get(i)).append('\n');
        }
        for (String line : added) {
            out.append('+').append(line).append('\n');
        }
        for (int i = insertAt; i < contextAfter; i++) {
            out.append(' ').append(original.get(i)).append('\n');
        }
        return out.toString();
    }
}
