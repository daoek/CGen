package com.daoekinc.pinfit.model;

import com.daoekinc.pinfit.PinfitException;
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
            throw new PinfitException(contextName + ".kind must be observer");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "observer");
        String name = common.name();
        String interfaceName = Values.identifier(Values.requiredString(yaml, "interface", contextName), contextName + ".interface");
        int capacity = Values.optionalInt(yaml, "capacity", 8, contextName);
        if (capacity < 1) {
            throw new PinfitException(contextName + ".capacity must be at least 1");
        }

        return new ObserverSpec(source, name, common.description(), common.header(), common.sourceFile(),
                common.includes(), interfaceName, capacity, common.context());
    }
}
