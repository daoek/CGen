package com.daoekinc.cgen.model;

import com.daoekinc.cgen.CGenException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record StatusCodesSpec(
        Path source,
        String name,
        String description,
        String header,
        List<String> includes,
        List<Code> codes,
        String successCode) {

    public record Code(String name, int value, String description) {
    }

    public static StatusCodesSpec from(Path source, Map<String, Object> yaml) {
        String contextName = source.toString();
        Values.onlyKeys(yaml, contextName, "kind", "name", "description", "header", "includes", "codes");
        if (!Values.requiredString(yaml, "kind", contextName).equals("status-codes")) {
            throw new CGenException(contextName + ".kind must be status-codes");
        }
        String name = Values.identifier(Values.requiredString(yaml, "name", contextName), contextName + ".name");
        String description = Values.optionalString(yaml, "description", name + " status codes", contextName);
        String header = Values.outputFile(Values.optionalString(yaml, "header", name + ".h", contextName), ".h",
                contextName + ".header");
        List<String> includes = Values.includeList(yaml, "includes", contextName);

        List<Code> codes = new ArrayList<>();
        List<Map<String, Object>> codeItems = Values.mapList(yaml, "codes", contextName,
                """
                codes:
                  - { name: OK, value: 0, description: Success }
                  - { name: INVALID_PARAM, value: -1 }""");
        for (int index = 0; index < codeItems.size(); index++) {
            Map<String, Object> item = codeItems.get(index);
            String itemContext = contextName + ".codes[" + index + "]";
            Values.onlyKeys(item, itemContext, "name", "value", "description");
            String codeName = Values.identifier(Values.requiredString(item, "name", itemContext), itemContext + ".name");
            Object valueObject = item.get("value");
            if (valueObject == null) {
                throw new CGenException(itemContext + ".value is required");
            }
            int value;
            if (valueObject instanceof Number number) {
                value = number.intValue();
            } else {
                try {
                    value = Integer.parseInt(valueObject.toString());
                } catch (NumberFormatException exception) {
                    throw new CGenException(itemContext + ".value must be an integer");
                }
            }
            codes.add(new Code(codeName, value, Values.optionalString(item, "description", "", itemContext)));
        }
        if (codes.isEmpty()) {
            throw new CGenException(contextName + ".codes needs at least one code");
        }
        Values.uniqueNames(codes.stream().map(Code::name).toList(), contextName + ".codes");
        Values.uniqueNames(codes.stream().map(code -> Integer.toString(code.value())).toList(),
                contextName + ".codes (values)");
        List<Code> successCandidates = codes.stream().filter(code -> code.value() == 0).toList();
        if (successCandidates.isEmpty()) {
            throw new CGenException(contextName + ".codes must contain exactly one code with value 0 (the success code)");
        }
        String successCode = successCandidates.get(0).name();

        return new StatusCodesSpec(source, name, description, header, includes, List.copyOf(codes), successCode);
    }
}
