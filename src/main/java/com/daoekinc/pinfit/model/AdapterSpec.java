package com.daoekinc.pinfit.model;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record AdapterSpec(
        Path source,
        String name,
        String description,
        String header,
        String sourceFile,
        List<String> includes,
        String from,
        String to,
        List<InterfaceSpec.Field> context,
        List<Mapping> mappings) {

    public record Mapping(String from, String to) {
    }

    public static AdapterSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "includes",
                "from", "to", "context", "mappings");
        if (!Values.requiredString(yaml, "kind", contextName).equals("adapter")) {
            throw new PinfitException(contextName + ".kind must be adapter");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "adapter");
        String name = common.name();
        String from = Values.identifier(Values.requiredString(yaml, "from", contextName), contextName + ".from");
        String to = Values.identifier(Values.requiredString(yaml, "to", contextName), contextName + ".to");
        if (from.equals(to)) {
            throw new PinfitException(contextName + ".from and .to must reference different interfaces");
        }

        List<Mapping> mappings = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "mappings", contextName,
                """
                mappings:
                  - { from: write, to: send }""")) {
            String itemContext = contextName + ".mappings";
            Values.onlyKeys(item, itemContext, "from", "to");
            mappings.add(new Mapping(
                    Values.identifier(Values.requiredString(item, "from", itemContext), itemContext + ".from"),
                    Values.identifier(Values.requiredString(item, "to", itemContext), itemContext + ".to")));
        }
        Values.uniqueNames(mappings.stream().map(Mapping::from).toList(), contextName + ".mappings (from)");

        return new AdapterSpec(source, name, common.description(), common.header(), common.sourceFile(),
                common.includes(), from, to, common.context(), List.copyOf(mappings));
    }
}
