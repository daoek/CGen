package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record InterfaceSpec(
        Path source,
        String name,
        String description,
        String header,
        String invalidReturn,
        String uninitializedReturn,
        List<String> includes,
        List<EnumDef> enums,
        List<StructDef> structs,
        List<Function> functions) {

    public record EnumDef(String name, String description, List<EnumValue> values) {
    }

    public record EnumValue(String name, String value) {
    }

    public record StructDef(String name, String description, List<Field> fields) {
    }

    public record Field(String type, String name, String description) {
    }

    public record Function(String name, String returnType, String description, List<Parameter> parameters,
                           String invalidReturn, String uninitializedReturn) {
    }

    public record Parameter(String type, String name, String description) {
    }

    public static InterfaceSpec from(Path source, Map<String, Object> yaml) {
        String context = source.toString();
        Values.onlyKeys(yaml, context, "kind", "name", "description", "header", "invalidReturn",
                "uninitializedReturn", "includes", "enums", "structs", "functions");
        String kind = Values.requiredString(yaml, "kind", context);
        if (!kind.equals("interface")) {
            throw new CGenException(context + ".kind must be interface");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", context), context + ".name");
        String description = Values.optionalString(yaml, "description", name + " interface", context);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + "_I.h", context), ".h", context + ".header");
        String invalidReturn = Values.optionalString(yaml, "invalidReturn", "-1", context);
        String uninitializedReturn = Values.optionalString(yaml, "uninitializedReturn", invalidReturn, context);
        List<String> includes = Values.stringList(yaml, "includes", context);

        List<EnumDef> enums = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "enums", context)) {
            String itemContext = context + ".enums";
            Values.onlyKeys(item, itemContext, "name", "description", "values");
            String enumName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            List<EnumValue> values = new ArrayList<>();
            for (Map<String, Object> value : Values.mapList(item, "values", itemContext)) {
                Values.onlyKeys(value, itemContext + ".values", "name", "value");
                values.add(new EnumValue(
                        Values.identifier(Values.requiredString(value, "name", itemContext + ".values"), itemContext + ".values.name"),
                        Values.optionalString(value, "value", null, itemContext + ".values")));
            }
            if (values.isEmpty()) {
                throw new CGenException(itemContext + " enum " + enumName + " needs at least one value");
            }
            Values.uniqueNames(values.stream().map(EnumValue::name).toList(), itemContext + " enum " + enumName);
            enums.add(new EnumDef(enumName, Values.optionalString(item, "description", "", itemContext), List.copyOf(values)));
        }

        List<StructDef> structs = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "structs", context)) {
            String itemContext = context + ".structs";
            Values.onlyKeys(item, itemContext, "name", "description", "fields");
            String structName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            List<Field> fields = parseFields(item, "fields", itemContext);
            if (fields.isEmpty()) {
                throw new CGenException(itemContext + " struct " + structName + " needs at least one field");
            }
            structs.add(new StructDef(structName, Values.optionalString(item, "description", "", itemContext), fields));
        }

        List<Function> functions = new ArrayList<>();
        List<Map<String, Object>> functionItems = Values.mapList(yaml, "functions", context);
        for (int functionIndex = 0; functionIndex < functionItems.size(); functionIndex++) {
            Map<String, Object> item = functionItems.get(functionIndex);
            String itemContext = context + ".functions[" + functionIndex + "]";
            Values.onlyKeys(item, itemContext, "name", "return", "description", "parameters", "invalidReturn", "uninitializedReturn");
            String functionName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            String returnType = oneLine(Values.optionalString(item, "return", "void", itemContext), itemContext + ".return");
            List<Parameter> parameters = new ArrayList<>();
            List<Map<String, Object>> parameterItems = Values.mapList(item, "parameters", itemContext,
                    """
                    parameters:
                      - { type: uint8_t *, name: buffer }
                      - { type: uint32_t, name: len }""");
            for (int parameterIndex = 0; parameterIndex < parameterItems.size(); parameterIndex++) {
                Map<String, Object> parameter = parameterItems.get(parameterIndex);
                String parameterContext = itemContext + ".parameters[" + parameterIndex + "]";
                Values.onlyKeys(parameter, parameterContext, "type", "name", "description");
                parameters.add(new Parameter(
                        oneLine(Values.requiredString(parameter, "type", parameterContext), parameterContext + ".type"),
                        Values.identifier(Values.requiredString(parameter, "name", parameterContext), parameterContext + ".name"),
                        Values.optionalString(parameter, "description", "", parameterContext)));
            }
            Values.uniqueNames(parameters.stream().map(Parameter::name).toList(), itemContext + " function " + functionName);
            functions.add(new Function(functionName, returnType,
                    Values.optionalString(item, "description", functionName, itemContext), List.copyOf(parameters),
                    Values.optionalString(item, "invalidReturn", invalidReturn, itemContext),
                    Values.optionalString(item, "uninitializedReturn", uninitializedReturn, itemContext)));
        }
        Values.uniqueNames(functions.stream().map(Function::name).toList(), context + ".functions");
        Values.uniqueNames(enums.stream().map(EnumDef::name).toList(), context + ".enums");
        Values.uniqueNames(structs.stream().map(StructDef::name).toList(), context + ".structs");
        return new InterfaceSpec(source, name, description, header, invalidReturn, uninitializedReturn,
                includes, List.copyOf(enums), List.copyOf(structs), List.copyOf(functions));
    }

    static List<Field> parseFields(Map<String, Object> map, String key, String context) {
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> field : Values.mapList(map, key, context)) {
            Values.onlyKeys(field, context + "." + key, "type", "name", "description");
            fields.add(new Field(
                    oneLine(Values.requiredString(field, "type", context + "." + key), context + "." + key + ".type"),
                    Values.identifier(Values.requiredString(field, "name", context + "." + key), context + "." + key + ".name"),
                    Values.optionalString(field, "description", "", context + "." + key)));
        }
        Values.uniqueNames(fields.stream().map(Field::name).toList(), context + "." + key);
        return List.copyOf(fields);
    }

    static String oneLine(String value, String context) {
        if (value.isBlank() || value.contains("\n") || value.contains("\r") || value.contains(";") || value.contains("{") || value.contains("}")) {
            throw new CGenException(context + " must be a safe one-line C declaration fragment");
        }
        return value;
    }
}
