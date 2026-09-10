package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CommandTableSpec(
        Path source,
        String name,
        String description,
        String header,
        String sourceFile,
        List<String> includes,
        List<InterfaceSpec.Field> context,
        List<Command> commands) {

    public record Command(String name, int opcode, String description) {
    }

    public static CommandTableSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "includes",
                "context", "commands");
        if (!Values.requiredString(yaml, "kind", contextName).equals("command-table")) {
            throw new CGenException(contextName + ".kind must be command-table");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", contextName), contextName + ".name");
        String description = Values.optionalString(yaml, "description", name + " command table", contextName);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + ".h", contextName), ".h",
                contextName + ".header");
        String sourceFile = Values.outputFile(Values.optionalString(yaml, "source", name + ".c", contextName), ".c",
                contextName + ".source");
        List<String> includes = Values.stringList(yaml, "includes", contextName);
        List<InterfaceSpec.Field> context = InterfaceSpec.parseFields(yaml, "context", contextName);

        List<Map<String, Object>> commandItems = Values.mapList(yaml, "commands", contextName,
                """
                commands:
                  - { name: PING, opcode: 0 }
                  - { name: RESET, opcode: 1 }""");
        if (commandItems.isEmpty()) {
            throw new CGenException(contextName + ".commands needs at least one command");
        }
        int explicitCount = 0;
        for (Map<String, Object> item : commandItems) {
            if (item.get("opcode") != null) {
                explicitCount++;
            }
        }
        if (explicitCount != 0 && explicitCount != commandItems.size()) {
            throw new CGenException(contextName + ".commands must either give every command an explicit opcode or none at all");
        }
        boolean explicitOpcodes = explicitCount == commandItems.size();

        List<Command> commands = new ArrayList<>();
        for (int index = 0; index < commandItems.size(); index++) {
            Map<String, Object> item = commandItems.get(index);
            String itemContext = contextName + ".commands[" + index + "]";
            Values.onlyKeys(item, itemContext, "name", "opcode", "description");
            String commandName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            int opcode;
            if (explicitOpcodes) {
                Object opcodeObject = item.get("opcode");
                if (opcodeObject instanceof Number number) {
                    opcode = number.intValue();
                } else {
                    try {
                        opcode = Integer.parseInt(opcodeObject.toString());
                    } catch (NumberFormatException exception) {
                        throw new CGenException(itemContext + ".opcode must be an integer");
                    }
                }
            } else {
                opcode = index;
            }
            commands.add(new Command(commandName, opcode, Values.optionalString(item, "description", "", itemContext)));
        }
        Values.uniqueNames(commands.stream().map(Command::name).toList(), contextName + ".commands");
        Values.uniqueNames(commands.stream().map(command -> Integer.toString(command.opcode())).toList(),
                contextName + ".commands (opcodes)");

        return new CommandTableSpec(source, name, description, header, sourceFile, includes, context, List.copyOf(commands));
    }
}
