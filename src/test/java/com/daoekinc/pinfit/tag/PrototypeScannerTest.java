package com.daoekinc.pinfit.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PrototypeScannerTest {
    private static final String HEADER = """
            /*@Pinfit(file:module-source:foo.c)*/
            #include "foo.h"

            /*@Pinfit usercode+ module.source.includes*/
            /*@Pinfit usercode-*/
            /*@Pinfit usercode+ module.source.variables*/
            /*@Pinfit usercode-*/
            /*@Pinfit usercode+ module.source.prototypes*/
            /*@Pinfit usercode-*/

            """;

    @Test
    void findsUserWrittenFunctionMissingAPrototype() {
        String content = HEADER + """
                /*@Pinfit usercode+ module.source.footer*/
                static void helper_function(int x)
                {
                    (void)x;
                }
                /*@Pinfit usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertEquals(1, scan.missing().size());
        assertEquals("helper_function", scan.missing().get(0).name());
        assertEquals("static void helper_function(int x);", scan.missing().get(0).signature());
        assertTrue(scan.updatedContent().contains("""
                /*@Pinfit usercode+ module.source.prototypes*/
                static void helper_function(int x);
                /*@Pinfit usercode-*/"""));
        assertTrue(scan.diff().contains("+static void helper_function(int x);"));
        assertTrue(scan.diff().startsWith("--- a/foo.c\n+++ b/foo.c\n@@ "));
    }

    @Test
    void ignoresFunctionThatAlreadyHasAPrototype() {
        String content = HEADER + """
                /*@Pinfit usercode+ module.source.footer*/
                static void helper_function(int x);

                static void helper_function(int x)
                {
                    (void)x;
                }
                /*@Pinfit usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertTrue(scan.isEmpty());
        assertEquals(List.of(), scan.missing());
    }

    @Test
    void ignoresCodeOutsideUsercodeRegions() {
        String content = HEADER + """
                /*@Pinfit(function-prototypes:foo)*/
                static void generated_helper(int x);

                /*@Pinfit(function:generated_helper)*/
                static void generated_helper(int x)
                {
                    (void)x;
                }
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertTrue(scan.isEmpty());
    }

    @Test
    void ignoresControlFlowStatementsShapedLikeFunctions() {
        String content = HEADER + """
                /*@Pinfit usercode+ module.source.footer*/
                static void helper_function(int x)
                {
                    if (x > 0)
                    {
                        x--;
                    }
                    else if (x < 0)
                    {
                        x++;
                    }
                }
                /*@Pinfit usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertEquals(1, scan.missing().size());
        assertEquals("helper_function", scan.missing().get(0).name());
    }

    @Test
    void doesNothingWhenFileIsNotPinfitGenerated() {
        String content = """
                static void helper_function(int x)
                {
                    (void)x;
                }
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "plain.c");

        assertTrue(scan.isEmpty());
        assertEquals(content, scan.updatedContent());
    }

    @Test
    void skipsCommentsDirectivesControlFlowAndNonDefinitions() {
        String content = HEADER + """
                /*@Pinfit usercode+ module.source.footer*/

                #define HELPER(x) (x)
                // void commented(void) {
                /* void block_comment(void) { */
                 * void doc_line(void) {
                if (ready) {
                while (busy)
                {
                }
                call_something(1)
                no_return_type(void) {
                void opens_later(void)
                int not_a_brace;
                static int twice(void) {
                static int twice(void) {
                void same_line(void) { return; }
                int *(void) {
                /*@Pinfit usercode-*/
                /*@Pinfit usercode-*/
                /*@Pinfit usercode+ module.source.last*/
                void last_line_without_brace(void)""";

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertEquals(List.of("twice"), scan.missing().stream().map(PrototypeScanner.Missing::name).toList());
        assertEquals("static int twice(void);", scan.missing().get(0).signature());
    }

    @Test
    void emptyParameterListBecomesVoidWhenBraceOpensOnTheSameLine() {
        String content = HEADER + """
                /*@Pinfit usercode+ module.source.footer*/
                static int helper() {
                    return 0;
                }
                /*@Pinfit usercode-*/
                """;

        assertEquals("static int helper(void);", PrototypeScanner.scan(content, "foo.c").missing().get(0).signature());
    }

    @Test
    void findsNothingToInsertWithoutAClosedPrototypesRegion() {
        String withoutRegion = """
                /*@Pinfit(file:module-source:foo.c)*/
                /*@Pinfit usercode+ module.source.footer*/
                static void helper(void)
                {
                }
                /*@Pinfit usercode-*/
                """;
        assertTrue(PrototypeScanner.scan(withoutRegion, "foo.c").isEmpty());

        String unclosedRegion = """
                /*@Pinfit(file:module-source:foo.c)*/
                /*@Pinfit usercode+ other*/
                static void helper(void)
                {
                }
                /*@Pinfit usercode+ module.source.prototypes*/
                """;
        assertTrue(PrototypeScanner.scan(unclosedRegion, "foo.c").isEmpty());
    }

    @Test
    void ignoresFilesPinfitDidNotGenerate() {
        assertTrue(PrototypeScanner.scan("static void helper(void)\n{\n}\n", "foo.c").isEmpty());
    }
}
