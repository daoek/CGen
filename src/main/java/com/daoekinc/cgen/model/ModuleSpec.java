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
        List<InterfaceSpec.EnumDef> enums,
        List<InterfaceSpec.Field> context,
        List<Variable> variables,
        List<Function> functions,
        boolean singleton,
        String instanceName,
        boolean singletonElse) {

    public record Variable(String type, String name, String description, Visibility visibility, String initial) {
    }

    public record Function(InterfaceSpec.Function spec, Visibility visibility) {
    }

    public enum Visibility {
        PUBLIC,
        PRIVATE,
        GET,
        SET
    }

    public static ModuleSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "source", "implements",
                "includes", "enums", "context", "variables", "functions", "singleton", "instance", "singletonElse");
        if (!Values.requiredString(yaml, "kind", contextName).equals("module")) {
            throw new CGenException(contextName + ".kind must be module");
        }
        Values.CommonFields common = Values.commonFields(yaml, contextName, "module");
        String name = common.name();
        List<InterfaceSpec.EnumDef> enums = InterfaceSpec.parseEnums(yaml, "enums", contextName);
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
                throw new CGenException(itemContext + ".visibility must be public, private, get, or set");
            }
            String initial = Values.optionalString(item, "initial", null, itemContext);
            if (initial != null && (initial.contains("\n") || initial.contains("\r") || initial.contains(";"))) {
                throw new CGenException(itemContext + ".initial must be a one-line C expression");
            }
            variables.add(new Variable(
                    InterfaceSpec.oneLine(Values.requiredString(item, "type", itemContext), itemContext + ".type"),
                    Values.variableDeclaratorName(Values.requiredString(item, "name", itemContext), itemContext + ".name"),
                    Values.optionalString(item, "description", "", itemContext), visibility,
                    initial));
        }
        Values.uniqueNames(variables.stream().map(Variable::name).toList(), contextName + ".variables");

        List<Function> functions = new ArrayList<>();
        List<Map<String, Object>> functionItems = Values.mapList(yaml, "functions", contextName);
        for (int index = 0; index < functionItems.size(); index++) {
            Map<String, Object> item = functionItems.get(index);
            String itemContext = contextName + ".functions[" + index + "]";
            Values.onlyKeys(item, itemContext, "name", "return", "description", "parameters", "invalidReturn",
                    "uninitializedReturn", "visibility");
            String visibilityText = Values.optionalString(item, "visibility", "private", itemContext).toUpperCase();
            Visibility visibility;
            try {
                visibility = Visibility.valueOf(visibilityText);
            } catch (IllegalArgumentException exception) {
                visibility = null;
            }
            if (visibility != Visibility.PUBLIC && visibility != Visibility.PRIVATE) {
                throw new CGenException(itemContext + ".visibility must be public or private");
            }
            functions.add(new Function(InterfaceSpec.parseFunctionItem(item, itemContext, null, null), visibility));
        }
        Values.uniqueNames(functions.stream().map(function -> function.spec().name()).toList(), contextName + ".functions");

        boolean singleton = Boolean.parseBoolean(Values.optionalString(yaml, "singleton", "false", contextName));
        String instanceName = Values.identifier(
                Values.optionalString(yaml, "instance", name + "_instance", contextName), contextName + ".instance");
        boolean singletonElse = Boolean.parseBoolean(Values.optionalString(yaml, "singletonElse", "false", contextName));
        return new ModuleSpec(source, name, common.description(), common.header(), common.sourceFile(),
                List.copyOf(implemented), common.includes(), enums, common.context(), List.copyOf(variables), List.copyOf(functions),
                singleton, instanceName, singletonElse);
    }
}
