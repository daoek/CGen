package com.daoekinc.cgen.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PrototypeScannerTest {
    private static final String HEADER = """
            /*@CGen(file:module-source:foo.c)*/
            #include "foo.h"

            /*@CGen usercode+ module.source.includes*/
            /*@CGen usercode-*/
            /*@CGen usercode+ module.source.variables*/
            /*@CGen usercode-*/
            /*@CGen usercode+ module.source.prototypes*/
            /*@CGen usercode-*/

            """;

    @Test
    void findsUserWrittenFunctionMissingAPrototype() {
        String content = HEADER + """
                /*@CGen usercode+ module.source.footer*/
                static void helper_function(int x)
                {
                    (void)x;
                }
                /*@CGen usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertEquals(1, scan.missing().size());
        assertEquals("helper_function", scan.missing().get(0).name());
        assertEquals("static void helper_function(int x);", scan.missing().get(0).signature());
        assertTrue(scan.updatedContent().contains("""
                /*@CGen usercode+ module.source.prototypes*/
                static void helper_function(int x);
                /*@CGen usercode-*/"""));
        assertTrue(scan.diff().contains("+static void helper_function(int x);"));
        assertTrue(scan.diff().startsWith("--- a/foo.c\n+++ b/foo.c\n@@ "));
    }

    @Test
    void ignoresFunctionThatAlreadyHasAPrototype() {
        String content = HEADER + """
                /*@CGen usercode+ module.source.footer*/
                static void helper_function(int x);

                static void helper_function(int x)
                {
                    (void)x;
                }
                /*@CGen usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertTrue(scan.isEmpty());
        assertEquals(List.of(), scan.missing());
    }

    @Test
    void ignoresCodeOutsideUsercodeRegions() {
        String content = HEADER + """
                /*@CGen(function-prototypes:foo)*/
                static void generated_helper(int x);

                /*@CGen(function:generated_helper)*/
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
                /*@CGen usercode+ module.source.footer*/
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
                /*@CGen usercode-*/
                """;

        PrototypeScanner.Scan scan = PrototypeScanner.scan(content, "foo.c");

        assertEquals(1, scan.missing().size());
        assertEquals("helper_function", scan.missing().get(0).name());
    }

    @Test
    void doesNothingWhenFileIsNotCGenGenerated() {
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
}
