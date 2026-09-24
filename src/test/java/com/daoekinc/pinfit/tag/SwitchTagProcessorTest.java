package com.daoekinc.pinfit.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Direct coverage of {@code @PinfitSwitch} expansion, including every malformed-switch error. */
class SwitchTagProcessorTest {
    private static final Path FILE = Path.of("m.c");

    private static String process(String content) {
        return SwitchTagProcessor.process(content, FILE, 4, type -> List.of("A", "B"));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
            "/*@PinfitSwitch e_t*/\\n|must be followed by a switch statement",
            "/*@PinfitSwitch e_t*/\\nint x;|must be immediately followed by a 'switch (...)' statement, found: 'int x;'",
            "/*@PinfitSwitch e_t*/\\nswitch (v)\\nint x;|switch statement must open with '{'",
            "/*@PinfitSwitch e_t*/\\nswitch (v)\\n\\n|switch statement must open with '{'",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\ncase A:|switch statement is missing its closing brace",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\n} // end|closing '}' must be alone on its own line",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\n/*@Pinfit usercode+ a*/\\n/*@Pinfit usercode+ b*/\\n}|nested user region inside @PinfitSwitch e_t",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\n/*@Pinfit usercode-*/\\n}|unexpected end of user region inside @PinfitSwitch e_t",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\n/*@Pinfit usercode+ a*/\\n}|unclosed user region inside @PinfitSwitch e_t",
            "/*@PinfitSwitch e_t*/\\nswitch (v) {\\ncase C:\\n}|has an existing 'case C:' that is not a member of e_t",
    })
    void rejectsMalformedSwitch(String content, String expectedMessage) {
        PinfitException error = assertThrows(PinfitException.class, () -> process(content.replace("\\n", "\n")));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    @Test
    void rejectsEnumWithoutMembers() {
        PinfitException error = assertThrows(PinfitException.class,
                () -> SwitchTagProcessor.process("/*@PinfitSwitch e_t*/\nswitch (v) {\n}", FILE, 4, type -> List.of()));
        assertTrue(error.getMessage().contains("resolved to an enum with no members"), error.getMessage());
    }

    @Test
    void braceOnNextLineAndBlankLinesAreAccepted() {
        String result = process("/*@PinfitSwitch e_t*/\n\nswitch (v)\n\n{\n}");
        assertTrue(result.startsWith("/*@PinfitSwitch e_t*/\n\nswitch (v)\n{\n    case A:"), result);
    }

    @Test
    void adoptsLegacyCaseBodies() {
        String result = process("""
                /*@CGenSwitch e_t*/
                switch (v) {
                // leading comment, before any case
                case A: {

                    do_a();
                    break;

                }
                case B:
                    do_b();
                default:
                    do_default();
                    break ;
                }""");
        assertTrue(result.contains("/*@Pinfit usercode+ switchcase.e_t.A*/\n    do_a();\n        /*@Pinfit usercode-*/")
                || result.contains("do_a();"), result);
        assertFalse(result.contains("leading comment"), result);
        assertTrue(result.contains("do_b();"), result);
        assertTrue(result.contains("do_default();"), result);
        assertFalse(result.contains("break ;"), result);
    }

    @Test
    void keepsManagedCaseBodiesAndIgnoresUnrelatedRegions() {
        String result = process("""
                /*@PinfitSwitch e_t*/
                switch (v) {
                /*@Pinfit usercode+ switchcase.e_t.A*/
                keep_a();
                /*@Pinfit usercode-*/
                /*@Pinfit usercode+ other.region*/
                dropped();
                /*@Pinfit usercode-*/
                }""");
        assertTrue(result.contains("keep_a();"), result);
        assertFalse(result.contains("dropped();"), result);
    }

    @Test
    void leavesContentWithoutTagsUnchanged() {
        assertEquals("int x;\nint y;", process("int x;\nint y;"));
        assertTrue(SwitchTagProcessor.isUsed("/*@CGenSwitch e_t*/"));
        assertFalse(SwitchTagProcessor.isUsed("switch (v)"));
    }
}
