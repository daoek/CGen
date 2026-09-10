package com.daoekinc.cgen.tag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CGenTag {
    private static final Pattern MARKER = Pattern.compile("^\\s*/\\*@CGen\\(([^)]*)\\)\\*/\\s*$");

    private CGenTag() {
    }

    public static String generatedFile(String kind, String source) {
        return "/*@CGen(file:" + kind + ":" + source + ")*/";
    }

    public static String generatedItem(String item, String name) {
        return "/*@CGen(" + item + ":" + name + ")*/";
    }

    public static String userBegin(String name) {
        return "/*@CGen(+" + name + ")*/";
    }

    public static String userEnd(String name) {
        return "/*@CGen(-" + name + ")*/";
    }

    public static boolean isGeneratedFile(String line) {
        String payload = payload(line);
        return payload != null && payload.startsWith("file:");
    }

    public static String userBeginName(String line) {
        String payload = payload(line);
        return payload != null && payload.startsWith("+") ? payload.substring(1) : null;
    }

    public static String userEndName(String line) {
        String payload = payload(line);
        return payload != null && payload.startsWith("-") ? payload.substring(1) : null;
    }

    private static String payload(String line) {
        Matcher matcher = MARKER.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1);
    }

    public static boolean isMarker(String line) {
        return MARKER.matcher(line).matches();
    }
}
