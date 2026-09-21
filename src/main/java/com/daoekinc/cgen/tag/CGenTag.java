package com.daoekinc.cgen.tag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CGenTag {
    // The optional leading "'" tolerates a PlantUML line comment (StateSmith diagrams - PlantUML
    // has no /* */ syntax) wrapping the same marker; no C output ever starts a line with "'", so
    // this is additive and changes nothing for any other generated file.
    private static final Pattern MARKER = Pattern.compile("^\\s*(?:'\\s*)?/\\*@CGen\\(([^)]*)\\)\\*/\\s*$");
    private static final Pattern USER_BEGIN = Pattern.compile("^\\s*/\\*@CGen usercode\\+ (\\S+)\\*/\\s*$");
    private static final Pattern USER_END = Pattern.compile("^\\s*/\\*@CGen usercode-\\*/\\s*$");
    // The pre-migration user-region syntax ("/*@CGen(+name)*/ ... /*@CGen(-name)*/"), replaced
    // project-wide by usercode+/usercode- before CGen 1.0. No current renderer emits this, but a
    // file written by that old version and never regenerated since would still have it on disk -
    // see TagHelper's use of these, which refuses to touch such a file rather than silently
    // discarding what's inside it (extract() otherwise treats an unrecognized marker line as
    // plain body text, i.e. as if the region never existed).
    private static final Pattern OLD_USER_BEGIN = Pattern.compile("^\\s*/\\*@CGen\\(\\+(\\S+)\\)\\*/\\s*$");
    private static final Pattern OLD_USER_END = Pattern.compile("^\\s*/\\*@CGen\\(-(\\S+)\\)\\*/\\s*$");
    // Written as the file's second line (right after the file marker) by TagHelper.writeGenerated,
    // and compared on the next readForGeneration to detect a hand-edit outside every usercode
    // region - see TagHelper for the full mechanism. Same optional leading "'" as MARKER, so it
    // stays valid when the file marker itself is PlantUML-commented (a .plantuml file).
    private static final Pattern SKELETON_HASH = Pattern.compile("^\\s*(?:'\\s*)?/\\*@CGen\\(skeleton-hash:([0-9a-fA-F]+)\\)\\*/\\s*$");

    private CGenTag() {
    }

    public static String generatedFile(String kind, String source) {
        return "/*@CGen(file:" + kind + ":" + source + ")*/";
    }

    public static String generatedItem(String item, String name) {
        return "/*@CGen(" + item + ":" + name + ")*/";
    }

    public static String userBegin(String name) {
        return "/*@CGen usercode+ " + name + "*/";
    }

    public static String userEnd() {
        return "/*@CGen usercode-*/";
    }

    public static boolean isGeneratedFile(String line) {
        String payload = payload(line);
        return payload != null && payload.startsWith("file:");
    }

    public static String userBeginName(String line) {
        Matcher matcher = USER_BEGIN.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    public static boolean isUserEnd(String line) {
        return USER_END.matcher(line).matches();
    }

    /** The region name from an old-syntax "/*@CGen(+name)*&#47;" begin line, or null. */
    public static String oldUserBeginName(String line) {
        Matcher matcher = OLD_USER_BEGIN.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The region name from an old-syntax "/*@CGen(-name)*&#47;" end line, or null. */
    public static String oldUserEndName(String line) {
        Matcher matcher = OLD_USER_END.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    public static String skeletonHash(String hash) {
        return "/*@CGen(skeleton-hash:" + hash + ")*/";
    }

    /** The hash from a "/*@CGen(skeleton-hash:...)*&#47;" line, or null if the line isn't one. */
    public static String skeletonHashValue(String line) {
        Matcher matcher = SKELETON_HASH.matcher(line);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The region name from an "/*@CGen(orphaned-user-region:name)*&#47;" line, or null. */
    public static String orphanedRegionName(String line) {
        String payload = payload(line);
        String prefix = "orphaned-user-region:";
        return payload != null && payload.startsWith(prefix) ? payload.substring(prefix.length()) : null;
    }

    private static String payload(String line) {
        Matcher matcher = MARKER.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    public static boolean isMarker(String line) {
        return MARKER.matcher(line).matches() || USER_BEGIN.matcher(line).matches() || USER_END.matcher(line).matches()
                || SKELETON_HASH.matcher(line).matches();
    }
}
