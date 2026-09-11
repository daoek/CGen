package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ModuleSpec(
        Path source,
        String name,
        String description,
        String header,
        String sourceFile,
        List<String> implementsInterfaces,
        List<String> includes,
        List<InterfaceSpec.Field> context,
        List<Variable> variables,
        boolean singleton) {

    public record Variable(String type, String name, String description, Visibility visibility, String initial) {
    }

    public enum Visibility {
        PUBLIC,
        PRIVATE
    }

    public static ModuleSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "implements",
                "includes", "context", "variables", "singleton");
        if (!Values.requiredString(yaml, "kind", contextName).equals("module")) {
            throw new CGenException(contextName + ".kind must be module");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "module");
        String name = common.name();
        List<String> implemented = Values.stringList(yaml, "implements", contextName).stream()
                .map(value -> Values.identifier(value, contextName + ".implements")).toList();
        Values.uniqueNames(implemented, contextName + ".implements");

        List<Variable> variables = new ArrayList<>();
        List<Map<String, Object>> variableItems = Values.itemList(yaml, "variables", contextName,
                """
                variables:
                  - uint32_t transfer_count public
                  - bool busy""", text -> Values.compactVariable(text, contextName + ".variables"));
        for (Map<String, Object> item : variableItems) {
            String itemContext = contextName + ".variables";
            Values.onlyKeys(item, itemContext, "type", "name", "description", "visibility", "initial");
            String visibilityText = Values.optionalString(item, "visibility", "private", itemContext).toUpperCase();
            Visibility visibility;
            try {
                visibility = Visibility.valueOf(visibilityText);
            } catch (IllegalArgumentException exception) {
                throw new CGenException(itemContext + ".visibility must be public or private");
            }
            String initial = Values.optionalString(item, "initial", null, itemContext);
            if (initial != null && (initial.contains("\n") || initial.contains("\r") || initial.contains(";"))) {
                throw new CGenException(itemContext + ".initial must be a one-line C expression");
            }
            variables.add(new Variable(
                    InterfaceSpec.oneLine(Values.requiredString(item, "type", itemContext), itemContext + ".type"),
                    Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name"),
                    Values.optionalString(item, "description", "", itemContext), visibility,
                    initial));
        }
        Values.uniqueNames(variables.stream().map(Variable::name).toList(), contextName + ".variables");
        boolean singleton = Boolean.parseBoolean(Values.optionalString(yaml, "singleton", "false", contextName));
        return new ModuleSpec(source, name, common.description(), common.header(), common.sourceFile(),
                List.copyOf(implemented), common.includes(), common.context(), List.copyOf(variables), singleton);
    }
}
