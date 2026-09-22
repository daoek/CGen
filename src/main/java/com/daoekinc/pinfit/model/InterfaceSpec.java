package com.daoekinc.pinfit.model;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                           String invalidReturn, String uninitializedReturn,
                           boolean invalidReturnIsFallback, boolean uninitializedReturnIsFallback) {
    }

    /**
     * File-level fallbacks for the guard return values of every function in one spec. A function
     * resolves its value from, in order: its own {@code invalidReturn} key, the {@code invalidReturns}
     * mapping entry for its return type, the scalar {@code invalidReturn}, and finally a zero
     * initializer for the return type. The typed mapping is what lets one file mix an {@code int32_t}
     * status default with a struct or enum return that {@code -1} would not compile for.
     */
    public record ReturnDefaults(String invalidReturn, String uninitializedReturn,
                                 Map<String, String> invalidByType, Map<String, String> uninitializedByType) {

        public static final ReturnDefaults NONE = new ReturnDefaults(null, null, Map.of(), Map.of());

        static ReturnDefaults from(Map<String, Object> yaml, String context) {
            String invalid = Values.optionalString(yaml, "invalidReturn", null, context);
            if (invalid != null) {
                invalid = oneLineExpression(invalid, context + ".invalidReturn");
            }
            String uninitialized = Values.optionalString(yaml, "uninitializedReturn", invalid, context);
            if (uninitialized != null) {
                uninitialized = oneLineExpression(uninitialized, context + ".uninitializedReturn");
            }
            Map<String, String> invalidByType = Values.stringMap(yaml, "invalidReturns", context);
            Map<String, String> uninitializedByType = new LinkedHashMap<>(invalidByType);
            uninitializedByType.putAll(Values.stringMap(yaml, "uninitializedReturns", context));
            return new ReturnDefaults(invalid, uninitialized, invalidByType, Map.copyOf(uninitializedByType));
        }

        String invalidFor(String returnType) {
            return invalidByType.getOrDefault(returnType, invalidReturn);
        }

        String uninitializedFor(String returnType) {
            return uninitializedByType.getOrDefault(returnType, uninitializedReturn);
        }
    }

    // Used when neither the function nor its file names a guard return value. A compound literal
    // zero-initializes any complete C type - scalar, enum, struct, union or pointer - so the
    // generated guard always compiles. It is a last resort: for an enum whose 0 value means
    // success, name a real sentinel through invalidReturns instead.
    static String zeroReturn(String returnType) {
        return "(" + returnType + "){0}";
    }

    public record Parameter(String type, String name, String description) {
    }

    public static InterfaceSpec from(Path source, Map<String, Object> yaml) {
        String context = source.toString();
        Values.onlyKeys(yaml, context, "kind", "name", "description", "header", "invalidReturn",
                "uninitializedReturn", "invalidReturns", "uninitializedReturns", "includes", "enums",
                "structs", "functions");
        String kind = Values.requiredString(yaml, "kind", context);
        if (!kind.equals("interface")) {
            throw new PinfitException(context + ".kind must be interface");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", context), context + ".name");
        String description = Values.optionalString(yaml, "description", name + " interface", context);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + "_I.h", context), ".h", context + ".header");
        ReturnDefaults returnDefaults = ReturnDefaults.from(yaml, context);
        String invalidReturn = returnDefaults.invalidReturn();
        String uninitializedReturn = returnDefaults.uninitializedReturn();
        List<String> includes = Values.includeList(yaml, "includes", context);

        List<EnumDef> enums = parseEnums(yaml, "enums", context);

        List<StructDef> structs = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, "structs", context)) {
            String itemContext = context + ".structs";
            Values.onlyKeys(item, itemContext, "name", "description", "fields");
            String structName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            List<Field> fields = parseFields(item, "fields", itemContext);
            if (fields.isEmpty()) {
                throw new PinfitException(itemContext + " struct " + structName + " needs at least one field");
            }
            structs.add(new StructDef(structName, Values.optionalString(item, "description", "", itemContext), fields));
        }

        List<Function> functions = parseFunctions(yaml, "functions", context, returnDefaults);
        Values.uniqueNames(structs.stream().map(StructDef::name).toList(), context + ".structs");
        return new InterfaceSpec(source, name, description, header, invalidReturn, uninitializedReturn,
                includes, List.copyOf(enums), List.copyOf(structs), List.copyOf(functions));
    }

    static List<EnumDef> parseEnums(Map<String, Object> yaml, String key, String context) {
        List<EnumDef> enums = new ArrayList<>();
        for (Map<String, Object> item : Values.mapList(yaml, key, context)) {
            String itemContext = context + "." + key;
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
                throw new PinfitException(itemContext + " enum " + enumName + " needs at least one value");
            }
            Values.uniqueNames(values.stream().map(EnumValue::name).toList(), itemContext + " enum " + enumName);
            enums.add(new EnumDef(enumName, Values.optionalString(item, "description", "", itemContext), List.copyOf(values)));
        }
        Values.uniqueNames(enums.stream().map(EnumDef::name).toList(), context + "." + key);
        return List.copyOf(enums);
    }

    static List<Function> parseFunctions(Map<String, Object> yaml, String key, String context,
                                         ReturnDefaults defaults) {
        List<Function> functions = new ArrayList<>();
        List<Map<String, Object>> functionItems = Values.mapList(yaml, key, context);
        for (int functionIndex = 0; functionIndex < functionItems.size(); functionIndex++) {
            Map<String, Object> item = functionItems.get(functionIndex);
            String itemContext = context + "." + key + "[" + functionIndex + "]";
            Values.onlyKeys(item, itemContext, "name", "return", "description", "parameters", "invalidReturn", "uninitializedReturn");
            functions.add(parseFunctionItem(item, itemContext, defaults));
        }
        Values.uniqueNames(functions.stream().map(Function::name).toList(), context + "." + key);
        return List.copyOf(functions);
    }

    // Parses the fields common to a "functions" list item, without enforcing which keys are
    // allowed - callers run their own Values.onlyKeys first, since a module function item
    // permits an extra "visibility" key that an interface function item does not.
    static Function parseFunctionItem(Map<String, Object> item, String itemContext, ReturnDefaults defaults) {
        String functionName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
        String returnType = oneLine(Values.optionalString(item, "return", "void", itemContext), itemContext + ".return");
        List<Parameter> parameters = parseParameters(item, "parameters", itemContext, "function " + functionName);
        String functionInvalidReturn = Values.optionalString(item, "invalidReturn",
                defaults.invalidFor(returnType), itemContext);
        if (functionInvalidReturn != null) {
            functionInvalidReturn = oneLineExpression(functionInvalidReturn, itemContext + ".invalidReturn");
        }
        String defaultUninitializedReturn = defaults.uninitializedFor(returnType);
        String functionUninitializedReturn = Values.optionalString(item, "uninitializedReturn",
                defaultUninitializedReturn != null ? defaultUninitializedReturn : functionInvalidReturn, itemContext);
        if (functionUninitializedReturn != null) {
            functionUninitializedReturn = oneLineExpression(functionUninitializedReturn, itemContext + ".uninitializedReturn");
        }
        // No value anywhere: zero-initialize the return type so the generated guard still compiles.
        // Tracked precisely here, at the moment of the decision, rather than guessed later by
        // comparing the final value against zeroReturn(returnType) - a function that explicitly
        // names that same expression as its real sentinel would otherwise look identical to one
        // that fell back to it.
        boolean invalidReturnIsFallback = false;
        boolean uninitializedReturnIsFallback = false;
        if (!returnType.equals("void")) {
            if (functionInvalidReturn == null) {
                functionInvalidReturn = zeroReturn(returnType);
                invalidReturnIsFallback = true;
            }
            if (functionUninitializedReturn == null) {
                functionUninitializedReturn = zeroReturn(returnType);
                uninitializedReturnIsFallback = true;
            }
        }
        return new Function(functionName, returnType,
                Values.optionalString(item, "description", functionName, itemContext), List.copyOf(parameters),
                functionInvalidReturn, functionUninitializedReturn, invalidReturnIsFallback, uninitializedReturnIsFallback);
    }

    static List<Field> parseFields(Map<String, Object> map, String key, String context) {
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> field : Values.itemList(map, key, context,
                key + ":\n  - uint32_t speed", text -> Values.compactField(text, context + "." + key))) {
            Values.onlyKeys(field, context + "." + key, "type", "name", "description");
            fields.add(new Field(
                    oneLine(Values.requiredString(field, "type", context + "." + key), context + "." + key + ".type"),
                    Values.identifier(Values.requiredString(field, "name", context + "." + key), context + "." + key + ".name"),
                    Values.optionalString(field, "description", "", context + "." + key)));
        }
        Values.uniqueNames(fields.stream().map(Field::name).toList(), context + "." + key);
        return List.copyOf(fields);
    }

    static List<Parameter> parseParameters(Map<String, Object> item, String key, String itemContext, String uniqueLabel) {
        List<Parameter> parameters = new ArrayList<>();
        List<Map<String, Object>> parameterItems = Values.itemList(item, key, itemContext,
                """
                parameters:
                  - uint8_t *buffer
                  - uint32_t len""", text -> Values.compactField(text, itemContext + "." + key));
        for (int parameterIndex = 0; parameterIndex < parameterItems.size(); parameterIndex++) {
            Map<String, Object> parameter = parameterItems.get(parameterIndex);
            String parameterContext = itemContext + "." + key + "[" + parameterIndex + "]";
            Values.onlyKeys(parameter, parameterContext, "type", "name", "description");
            parameters.add(new Parameter(
                    oneLine(Values.requiredString(parameter, "type", parameterContext), parameterContext + ".type"),
                    Values.identifier(Values.requiredString(parameter, "name", parameterContext), parameterContext + ".name"),
                    Values.optionalString(parameter, "description", "", parameterContext)));
        }
        Values.uniqueNames(parameters.stream().map(Parameter::name).toList(), itemContext + " " + uniqueLabel);
        return List.copyOf(parameters);
    }

    // Return values may be compound literals - "(flash_command_t){0}" - so braces are allowed
    // here even though they are not in a declaration fragment.
    static String oneLineExpression(String value, String context) {
        if (value.isBlank() || value.contains("\n") || value.contains("\r") || value.contains(";")) {
            throw new PinfitException(context + " must be a safe one-line C expression");
        }
        return value;
    }

    static String oneLine(String value, String context) {
        if (value.isBlank() || value.contains("\n") || value.contains("\r") || value.contains(";") || value.contains("{") || value.contains("}")) {
            throw new PinfitException(context + " must be a safe one-line C declaration fragment");
        }
        return value;
    }
}
