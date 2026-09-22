package com.daoekinc.pinfit.generate;

/**
 * The single place that decides the StateSmith-facing names for an {@code engine: statesmith}
 * machine, so {@link StateSmithPlantUmlRenderer}, {@link StateSmithApiRenderer},
 * {@link StateSmithHooksRenderer}, {@code PinfitGenerator} and {@code StateSmithRunner} can never
 * drift apart on them.
 *
 * <p>The PlantUML diagram is named {@code <name>_sm} - confirmed against a real {@code ss.cli}
 * run that this single choice drives both StateSmith's generated C identifier prefix
 * ({@code door_sm_ctor}, {@code door_sm_StateId_...}, ...) and its default output file names
 * ({@code door_sm.c}/{@code door_sm.h}, written next to the input file), with no separate
 * output-naming TOML setting needed. Folder B (the state machine's own {@code <name>_sm/}
 * subdirectory, entirely StateSmith-owned) uses that same name.
 */
final class StateSmithNaming {
    private StateSmithNaming() {
    }

    static String smIdentifier(String machineName) {
        return machineName + "_sm";
    }

    /** Folder B's name, inside the state machine's own directory (folder A). */
    static String smDirectoryName(String machineName) {
        return smIdentifier(machineName);
    }

    static String plantUmlFileName(String machineName) {
        return smIdentifier(machineName) + ".plantuml";
    }

    static String smHeaderFileName(String machineName) {
        return smIdentifier(machineName) + ".h";
    }

    static String smSourceFileName(String machineName) {
        return smIdentifier(machineName) + ".c";
    }

    static String hooksHeaderFileName(String machineName) {
        return machineName + "_hooks.h";
    }

    static String hooksSourceFileName(String machineName) {
        return machineName + "_hooks.c";
    }

    // StateSmith's confirmed C99 identifier names, all derived from the smIdentifier prefix.

    static String smCtor(String machineName) {
        return smIdentifier(machineName) + "_ctor";
    }

    static String smStart(String machineName) {
        return smIdentifier(machineName) + "_start";
    }

    static String smDispatchEvent(String machineName) {
        return smIdentifier(machineName) + "_dispatch_event";
    }

    static String smEventIdValue(String machineName, String eventName) {
        return smIdentifier(machineName) + "_EventId_" + eventName;
    }

    static String smStateIdValue(String machineName, String stateName) {
        return smIdentifier(machineName) + "_StateId_" + stateName;
    }
}
