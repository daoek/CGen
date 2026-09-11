package com.daoekinc.cgen.tag;

import com.daoekinc.cgen.CGenException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TagHelper {
    public UserRegions readForGeneration(Path output) {
        if (!Files.exists(output)) {
            return new UserRegions(Map.of());
        }
        try {
            String content = Files.readString(output);
            boolean generated = content.lines().anyMatch(CGenTag::isGeneratedFile);
            if (!generated) {
                throw new CGenException("Refusing to overwrite non-CGen file " + output);
            }
            return new UserRegions(extract(content, output));
        } catch (IOException exception) {
            throw new CGenException("Cannot read " + output + ": " + exception.getMessage(), exception);
        }
    }

    public void writeGenerated(Path output, String content, String lineEnding) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!lineEnding.equals("\n")) {
            normalized = normalized.replace("\n", lineEnding);
        }
        writeAtomic(output, normalized);
    }

    public boolean stripTags(Path file) {
        try {
            String content = Files.readString(file);
            String lineEnding = content.contains("\r\n") ? "\r\n" : "\n";
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String line : content.split("\\R", -1)) {
                if (CGenTag.isMarker(line)) {
                    changed = true;
                } else {
                    kept.add(line);
                }
            }
            if (changed) {
                writeAtomic(file, String.join(lineEnding, kept));
            }
            return changed;
        } catch (IOException exception) {
            throw new CGenException("Cannot strip tags from " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static Map<String, String> extract(String content, Path file) {
        Map<String, String> regions = new LinkedHashMap<>();
        String current = null;
        List<String> body = null;
        for (String line : content.split("\\R", -1)) {
            String beginName = CGenTag.userBeginName(line);
            if (beginName != null) {
                if (current != null) {
                    throw new CGenException("Nested user region in " + file);
                }
                current = beginName;
                if (current.isBlank() || regions.containsKey(current)) {
                    throw new CGenException("Invalid or duplicate user region in " + file);
                }
                body = new ArrayList<>();
            } else if (CGenTag.isUserEnd(line)) {
                if (current == null) {
                    throw new CGenException("Unexpected end of user region in " + file);
                }
                regions.put(current, String.join("\n", body));
                current = null;
                body = null;
            } else if (current != null) {
                body.add(line);
            }
        }
        if (current != null) {
            throw new CGenException("Unclosed user region '" + current + "' in " + file);
        }
        return regions;
    }

    private static void writeAtomic(Path output, String content) {
        try {
            Files.createDirectories(output.getParent());
            Path temporary = Files.createTempFile(output.getParent(), ".cgen-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new CGenException("Cannot write " + output + ": " + exception.getMessage(), exception);
        }
    }

    public static final class UserRegions {
        private final Map<String, String> values;
        private final Set<String> used = new LinkedHashSet<>();

        private UserRegions(Map<String, String> values) {
            this.values = new LinkedHashMap<>(values);
        }

        public String render(String name, String defaultBody) {
            return render(name, defaultBody, "");
        }

        /**
         * Same as {@link #render(String, String)}, but the begin/end marker lines are
         * prefixed with {@code indent} so they line up with the surrounding generated
         * code. Existing body content is left untouched (it's the user's, not ours to
         * reformat) — only the marker lines get the prefix.
         */
        public String render(String name, String defaultBody, String indent) {
            used.add(name);
            String body = values.getOrDefault(name, defaultBody);
            StringBuilder result = new StringBuilder(indent).append(CGenTag.userBegin(name)).append('\n');
            if (!body.isEmpty()) {
                result.append(body);
                if (!body.endsWith("\n")) {
                    result.append('\n');
                }
            }
            return result.append(indent).append(CGenTag.userEnd()).append('\n').toString();
        }

        public void removeIfMatches(String name, String generatedBody) {
            if (generatedBody.equals(values.get(name))) {
                values.remove(name);
            }
        }

        public String renderOrphans() {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (!used.contains(entry.getKey()) && !entry.getValue().isBlank()) {
                    result.append("\n").append(CGenTag.generatedItem("orphaned-user-region", entry.getKey())).append('\n');
                    result.append(render(entry.getKey(), entry.getValue()));
                }
            }
            return result.toString();
        }
    }
}
