package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ObserverSpec(
        Path source,
        String name,
        String description,
        String header,
        String sourceFile,
        List<String> includes,
        String interfaceName,
        int capacity,
        List<InterfaceSpec.Field> context) {

    public static ObserverSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "includes",
                "interface", "capacity", "context");
        if (!Values.requiredString(yaml, "kind", contextName).equals("observer")) {
            throw new CGenException(contextName + ".kind must be observer");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", contextName), contextName + ".name");
        String description = Values.optionalString(yaml, "description", name + " observer", contextName);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + ".h", contextName), ".h",
                contextName + ".header");
        String sourceFile = Values.outputFile(Values.optionalString(yaml, "source", name + ".c", contextName), ".c",
                contextName + ".source");
        List<String> includes = Values.stringList(yaml, "includes", contextName);
        String interfaceName = Values.identifier(Values.requiredString(yaml, "interface", contextName), contextName + ".interface");
        int capacity = Values.optionalInt(yaml, "capacity", 8, contextName);
        if (capacity < 1) {
            throw new CGenException(contextName + ".capacity must be at least 1");
        }
        List<InterfaceSpec.Field> context = InterfaceSpec.parseFields(yaml, "context", contextName);

        return new ObserverSpec(source, name, description, header, sourceFile, includes, interfaceName, capacity, context);
    }
}
