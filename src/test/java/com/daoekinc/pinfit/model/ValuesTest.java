package com.daoekinc.pinfit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.yaml.snakeyaml.Yaml;

/** Direct coverage of the shared YAML value helpers every spec parser builds on. */
class ValuesTest {
    @Test
    void optionalStringAcceptsScalarsAndRejectsCollections() {
        assertEquals("5", Values.optionalString(yaml("k: 5"), "k", null, "c"));
        assertEquals("true", Values.optionalString(yaml("k: true"), "k", null, "c"));
        assertEquals("fallback", Values.optionalString(yaml("other: 1"), "k", "fallback", "c"));
        assertFails(() -> Values.optionalString(yaml("k: [1]"), "k", null, "c"), "c.k must be text");
        assertFails(() -> Values.requiredString(yaml("k: ''"), "k", "c"), "c.k is required");
    }

    @Test
    void optionalIntParsesNumbersAndNumericText() {
        assertEquals(3, Values.optionalInt(yaml("k: 3"), "k", 0, "c"));
        assertEquals(4, Values.optionalInt(yaml("k: '4'"), "k", 0, "c"));
        assertEquals(9, Values.optionalInt(yaml("other: 1"), "k", 9, "c"));
        assertFails(() -> Values.optionalInt(yaml("k: four"), "k", 0, "c"), "c.k must be an integer");
    }

    @Test
    void optionalMapRequiresTextKeys() {
        assertEquals(Map.of(), Values.optionalMap(yaml("other: 1"), "k", "c"));
        assertFails(() -> Values.optionalMap(yaml("k: [1]"), "k", "c"), "c.k must be a mapping");
        assertFails(() -> Values.optionalMap(yaml("k: {1: a}"), "k", "c"), "c.k contains a non-text key");
    }

    @Test
    void optionalListWrapsAScalar() {
        assertEquals(List.of("x"), Values.optionalList(yaml("k: x"), "k", "c"));
        assertEquals(List.of(), Values.optionalList(yaml("other: 1"), "k", "c"));
    }

    @Test
    void stringListRejectsNonTextBlankAndMultiline() {
        assertFails(() -> Values.stringList(yaml("k: [1]"), "k", "c"), "must contain non-empty one-line strings");
        assertFails(() -> Values.stringList(yaml("k: [' ']"), "k", "c"), "must contain non-empty one-line strings");
        assertFails(() -> Values.stringList(yaml("k: [\"a\\nb\"]"), "k", "c"), "must contain non-empty one-line strings");
        assertFails(() -> Values.stringList(yaml("k: [\"a\\rb\"]"), "k", "c"), "must contain non-empty one-line strings");
    }

    @Test
    void includeListRequiresAngledOrQuotedIncludes() {
        assertEquals(List.of("<a.h>", "\"b.h\""), Values.includeList(yaml("k: ['<a.h>', '\"b.h\"']"), "k", "c"));
        for (String bad : new String[] {"b.h", "<", "\"", "<a.h", "\"a.h", "a.h>"}) {
            Map<String, Object> map = new HashMap<>();
            map.put("k", List.of(bad));
            PinfitException error = assertThrows(PinfitException.class, () -> Values.includeList(map, "k", "c"), bad);
            assertTrue(error.getMessage().contains("must be wrapped in <...> or \"...\""), error.getMessage());
        }
    }

    @Test
    void mapListRejectsScalarsWithAndWithoutExampleAndNonTextKeys() {
        PinfitException withExample = assertThrows(PinfitException.class, () -> Values.mapList(yaml("k: [x]"), "k", "c", "k: example"));
        assertEquals("c.k[0] must be a mapping", withExample.getMessage());
        assertEquals("k: example", withExample.helpText());
        PinfitException withoutExample = assertThrows(PinfitException.class, () -> Values.mapList(yaml("k: [x]"), "k", "c"));
        assertEquals(null, withoutExample.helpText());
        assertFails(() -> Values.mapList(yaml("k: [{1: a}]"), "k", "c"), "c.k[0] contains a non-text key");
    }

    @Test
    void itemListAcceptsCompactStringsAndMappings() {
        List<Map<String, Object>> items = Values.itemList(yaml("k: ['int a', {type: int, name: b}]"), "k", "c", null,
                text -> Values.compactField(text, "c"));
        assertEquals("a", items.get(0).get("name"));
        assertEquals("b", items.get(1).get("name"));
        assertFails(() -> Values.itemList(yaml("k: [{1: a}]"), "k", "c", null, text -> Map.of()), "c.k[0] contains a non-text key");
        PinfitException withoutExample = assertThrows(PinfitException.class,
                () -> Values.itemList(yaml("k: [1]"), "k", "c", null, text -> Map.of()));
        assertEquals("c.k[0] must be a mapping or a \"type name\" string", withoutExample.getMessage());
        assertEquals(null, withoutExample.helpText());
        PinfitException withExample = assertThrows(PinfitException.class,
                () -> Values.itemList(yaml("k: [1]"), "k", "c", "k: example", text -> Map.of()));
        assertEquals("k: example", withExample.helpText());
    }

    @Test
    void stringMapValidatesKeysAndValues() {
        assertEquals(Map.of("int", "0"), Values.stringMap(yaml("k: {' int ': 0}"), "k", "c"));
        assertFails(() -> Values.stringMap(yaml("k: {' ': x}"), "k", "c"), "c.k contains a blank key");
        assertFails(() -> Values.stringMap(yaml("k: {int: null}"), "k", "c"), "must be a non-empty one-line C expression");
        assertFails(() -> Values.stringMap(yaml("k: {int: ' '}"), "k", "c"), "must be a non-empty one-line C expression");
    }

    @Test
    void compactFieldSplitsTypeNameAndArraySuffix() {
        assertEquals(Map.of("type", "uint8_t", "name", "buffer[6]"), Values.compactField("uint8_t buffer[6]", "c"));
        assertEquals(Map.of("type", "char *", "name", "text"), Values.compactField("char *text", "c"));
        assertFails(() -> Values.compactField("speed", "c"), "c must be \"type name\", got 'speed'");
        assertFails(() -> Values.compactField("int *", "c"), "c must be \"type name\"");
        assertFails(() -> Values.compactField("int 9x", "c"), "c must be \"type name\"");
    }

    @Test
    void compactVariableKeepsATrailingVisibility() {
        assertEquals("get", Values.compactVariable("int count get", "c").get("visibility"));
        assertEquals(null, Values.compactVariable("int count", "c").get("visibility"));
    }

    @Test
    void identifierAndDeclaratorValidation() {
        assertEquals("buffer[6]", Values.variableDeclaratorName("buffer[6]", "c"));
        assertFails(() -> Values.variableDeclaratorName("6buffer", "c"), "optionally with array brackets");
        assertFails(() -> Values.identifier("a-b", "c"), "c must be a valid C identifier, got 'a-b'");
        assertFails(() -> Values.uniqueNames(List.of("a", "a"), "c"), "c contains duplicate name 'a'");
        assertFails(() -> Values.onlyKeys(yaml("x: 1"), "c", "y"), "c contains unknown key 'x'");
    }

    @Test
    void outputFileMustBeALocalFileWithTheRightSuffix() {
        assertEquals("a.h", Values.outputFile("a.h", ".h", "c"));
        for (String bad : new String[] {"d/a.h", "d\\a.h", ".", "..", "a.c"}) {
            assertFails(() -> Values.outputFile(bad, ".h", "c"), "c must be a local .h file name");
        }
    }

    private static Map<String, Object> yaml(String text) {
        return new Yaml().load(text);
    }

    private static void assertFails(Executable executable, String expectedMessage) {
        PinfitException error = assertThrows(PinfitException.class, executable);
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }
}
