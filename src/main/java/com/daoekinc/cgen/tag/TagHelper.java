package com.daoekinc.cgen.tag;

import com.daoekinc.cgen.CGenException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TagHelper {
    public UserRegions readForGeneration(Path output, boolean force) {
        if (!Files.exists(output)) {
            return new UserRegions(Map.of());
        }
        try {
            String content = Files.readString(output);
            boolean generated = content.lines().anyMatch(CGenTag::isGeneratedFile);
            if (!generated && !force) {
                throw new CGenException("Refusing to overwrite non-CGen file " + output + " (use -f/--force to overwrite)");
            }
            // extract() first: an unparseable (e.g. old-syntax) file gets that specific, more
            // actionable error, rather than the generic skeleton-mismatch one below - a file
            // that fails to parse at all will also usually fail the hash comparison, since the
            // hash is computed the same region-aware way.
            Map<String, String> regions = generated ? extract(content, output) : Map.of();
            if (generated) {
                requireSkeletonUnchanged(output, content, force);
            }
            return new UserRegions(regions);
        } catch (IOException exception) {
            throw new CGenException("Cannot read " + output + ": " + exception.getMessage(), exception);
        }
    }

    public void writeGenerated(Path output, String content, String lineEnding) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        normalized = insertSkeletonHash(normalized);
        if (!lineEnding.equals("\n")) {
            normalized = normalized.replace("\n", lineEnding);
        }
        writeAtomic(output, normalized);
    }

    /**
     * Refuses to overwrite {@code output} if its generated (non-usercode-region) content was
     * hand-edited since CGen last wrote it - caught by comparing the current file's "skeleton"
     * hash (every usercode region body blanked, so an edit *inside* a region never trips this)
     * against the one {@link #insertSkeletonHash} embedded on the file's second line at that last
     * write. A file from before this feature existed has no stored hash yet - skipped, not
     * flagged, and gets one on its next write.
     */
    private static void requireSkeletonUnchanged(Path output, String content, boolean force) {
        String[] firstTwoLines = firstTwoLines(content);
        if (firstTwoLines == null) {
            return;
        }
        String storedHash = CGenTag.skeletonHashValue(firstTwoLines[1]);
        if (storedHash == null) {
            return;
        }
        String rest = content.substring(firstTwoLines[0].length() + 1 + firstTwoLines[1].length() + 1);
        String currentHash = skeletonHash(rest);
        if (!force && !currentHash.equalsIgnoreCase(storedHash)) {
            throw new CGenException(output + " was edited outside its usercode regions since CGen last generated "
                    + "it - regenerating would silently overwrite that change.",
                    "Fix it by", "moving the change into a usercode region or into the YAML spec that describes "
                            + "it, or re-run generate with -f/--force to overwrite it anyway.");
        }
    }

    /** Embeds a hash of {@code content}'s skeleton as its second line, right after the file marker. */
    private static String insertSkeletonHash(String content) {
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) {
            return content;
        }
        String firstLine = content.substring(0, firstNewline);
        if (!CGenTag.isGeneratedFile(firstLine)) {
            return content;
        }
        String rest = content.substring(firstNewline + 1);
        // Match whatever comment style the file marker itself used - a PlantUML file's marker is
        // "'"-prefixed (no /* */ in PlantUML), and the hash line must be too, or it's invalid
        // syntax in that file instead of a harmless comment.
        String prefix = firstLine.stripLeading().startsWith("'") ? "' " : "";
        return firstLine + "\n" + prefix + CGenTag.skeletonHash(skeletonHash(rest)) + "\n" + rest;
    }

    /**
     * Content with every usercode region's body blanked (begin/end marker lines kept), so the
     * result changes only when something *outside* a region changes - what a hand-edit made
     * inside a region looks like to CGen is irrelevant here, that's the whole point of regions.
     */
    private static String stripRegionBodies(String content) {
        List<String> kept = new ArrayList<>();
        boolean inRegion = false;
        int depth = 0;
        for (String line : content.split("\\R", -1)) {
            String beginName = CGenTag.userBeginName(line);
            boolean isEnd = CGenTag.isUserEnd(line);
            if (!inRegion) {
                kept.add(line);
                if (beginName != null) {
                    inRegion = true;
                    depth = 1;
                }
                continue;
            }
            if (beginName != null) {
                depth++;
            } else if (isEnd) {
                depth--;
                if (depth == 0) {
                    inRegion = false;
                    kept.add(line);
                }
            }
        }
        return String.join("\n", kept);
    }

    private static String skeletonHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fullHash = digest.digest(stripRegionBodies(content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", fullHash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", exception);
        }
    }

    /** The file's first two lines, or null if it has fewer than two. */
    private static String[] firstTwoLines(String content) {
        int firstNewline = content.indexOf('\n');
        if (firstNewline < 0) {
            return null;
        }
        String rest = content.substring(firstNewline + 1);
        int secondNewline = rest.indexOf('\n');
        if (secondNewline < 0) {
            return null;
        }
        return new String[] {content.substring(0, firstNewline), rest.substring(0, secondNewline)};
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

    /**
     * A top-level user region's saved body is everything between its begin/end markers,
     * verbatim - including any inner begin/end marker lines a tool like @CGenSwitch left
     * there (e.g. per-case regions nested inside a function body). Those inner markers are
     * not extracted as regions of their own here; only the depth-0 begin/end pair is. A
     * marker's own tool (e.g. SwitchTagProcessor) re-parses that raw body text itself.
     */
    private static Map<String, String> extract(String content, Path file) {
        Map<String, String> regions = new LinkedHashMap<>();
        String current = null;
        List<String> body = null;
        int depth = 0;
        int lineNumber = 0;
        for (String line : content.split("\\R", -1)) {
            lineNumber++;
            requireNotOldSyntax(line, file, lineNumber);
            String beginName = CGenTag.userBeginName(line);
            boolean isEnd = CGenTag.isUserEnd(line);
            if (current == null) {
                if (beginName != null) {
                    if (beginName.isBlank() || regions.containsKey(beginName)) {
                        throw new CGenException("Invalid or duplicate user region in " + file);
                    }
                    current = beginName;
                    body = new ArrayList<>();
                    depth = 1;
                } else if (isEnd) {
                    throw new CGenException("Unexpected end of user region in " + file);
                }
                continue;
            }
            if (beginName != null) {
                depth++;
                body.add(line);
            } else if (isEnd) {
                depth--;
                if (depth == 0) {
                    regions.put(current, String.join("\n", body));
                    current = null;
                    body = null;
                } else {
                    body.add(line);
                }
            } else {
                body.add(line);
            }
        }
        if (current != null) {
            throw new CGenException("Unclosed user region '" + current + "' in " + file);
        }
        return regions;
    }

    /**
     * Refuses a file still using the pre-migration "/*@CGen(+name)*&#47; ... /*@CGen(-name)*&#47;"
     * region syntax. The current parser does not recognize those lines as region boundaries at
     * all - left unchecked, {@link #extract} would silently treat the whole region as ordinary
     * generated text, the code inside would never be captured, and the next {@code generate}
     * would overwrite it with nothing written back. Failing loudly here, before any region is
     * even parsed, is the only way to guarantee that never happens; unlike an automatic migration,
     * it never has to guess where a hand-written old-syntax region actually ends.
     */
    private static void requireNotOldSyntax(String line, Path file, int lineNumber) {
        String beginName = CGenTag.oldUserBeginName(line);
        String endName = CGenTag.oldUserEndName(line);
        String name = beginName != null ? beginName : endName;
        if (name == null) {
            return;
        }
        throw new CGenException(file + ":" + lineNumber + ": found the old user-region marker syntax "
                + "'/*@CGen(+" + name + ")*/ ... /*@CGen(-" + name + ")*/', which this version of CGen no longer "
                + "parses. Regenerating as-is would silently discard everything inside it.",
                "Fix it by", "changing just this region's two marker lines by hand to the current syntax - "
                        + "'/*@CGen usercode+ " + name + "*/' and '/*@CGen usercode-*/' - keeping the code "
                        + "between them exactly as it is, then running generate again.");
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

        /** Number of user regions read back from the previous generation of this file. */
        public int regionCount() {
            return values.size();
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
