package com.daoekinc.cgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file still using the pre-migration "/*@CGen(+name)*&#47; ... /*@CGen(-name)*&#47;" region
 * syntax must never be silently regenerated into losing the code inside it - CGen must refuse,
 * naming the file and line, so the user can migrate it by hand. See CGenTag/TagHelper.
 */
class LegacyMarkerSyntaxTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesToRegenerateOldSyntaxRegionAndNeverLosesItsCode() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("create", "module", "sensor_impl", "--implements", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("sensor_impl.c");
        String generated = Files.readString(source);
        assertTrue(generated.contains("/*@CGen usercode+ function.sensor.init.body*/"));

        String precious = "    return 42; /* PRECIOUS_USER_CODE */";
        String withUserCode = generated.replace(
                "/*@CGen usercode+ function.sensor.init.body*/\n",
                "/*@CGen usercode+ function.sensor.init.body*/\n" + precious + "\n");
        Files.writeString(source, withUserCode);

        // Simulate a file untouched since an old CGen version wrote it: flip just this one
        // region's two marker lines to the pre-migration syntax, verbatim body kept.
        String oldSyntax = Files.readString(source)
                .replace("/*@CGen usercode+ function.sensor.init.body*/", "/*@CGen(+function.sensor.init.body)*/")
                .replace("/*@CGen usercode-*/\n    return cgen_result;",
                        "/*@CGen(-function.sensor.init.body)*/\n    return cgen_result;");
        Files.writeString(source, oldSyntax);
        assertTrue(oldSyntax.contains(precious), "test setup sanity check");

        int exitCode = cli.run("generate");

        assertEquals(1, exitCode, "generate must refuse, not silently regenerate");
        assertTrue(cli.errors().contains("sensor_impl.c"), cli.errors());
        assertTrue(cli.errors().contains("old user-region marker syntax"), cli.errors());
        assertTrue(cli.errors().contains("function.sensor.init.body"), cli.errors());

        String onDiskAfterRefusal = Files.readString(source);
        assertTrue(onDiskAfterRefusal.contains(precious), "refusing must never touch the file on disk");
        assertEquals(oldSyntax, onDiskAfterRefusal, "file must be byte-for-byte untouched after a refusal");
    }

    @Test
    void forceDoesNotBypassTheOldSyntaxRefusal() throws Exception {
        CliFixture cli = new CliFixture(temporaryDirectory);
        assertEquals(0, cli.run("init"));
        assertEquals(0, cli.run("create", "interface", "sensor"));
        assertEquals(0, cli.run("create", "module", "sensor_impl", "--implements", "sensor"));
        assertEquals(0, cli.run("generate"));

        Path source = temporaryDirectory.resolve("sensor_impl.c");
        String oldSyntax = Files.readString(source)
                .replace("/*@CGen usercode+ function.sensor.init.body*/", "/*@CGen(+function.sensor.init.body)*/")
                .replace("/*@CGen usercode-*/\n    return cgen_result;",
                        "/*@CGen(-function.sensor.init.body)*/\n    return cgen_result;");
        Files.writeString(source, oldSyntax);

        assertEquals(1, cli.run("generate", "-f"),
                "force is for overwriting a non-CGen file, not for bypassing loss of recognized-but-outdated regions");
        assertEquals(oldSyntax, Files.readString(source));
    }
}
