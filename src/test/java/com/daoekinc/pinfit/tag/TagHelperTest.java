package com.daoekinc.pinfit.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.daoekinc.pinfit.PinfitException;
import com.daoekinc.pinfit.tag.TagHelper.UserRegions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Region parsing, skeleton-hash and file-writing edge cases of {@link TagHelper} and {@link PinfitTag}. */
class TagHelperTest {
    private static final String MARKER = "/*@Pinfit(file:module-source:m.module.yaml)*/";

    @TempDir
    Path temporaryDirectory;

    private final TagHelper tags = new TagHelper();

    private Path file(String name, String content) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static void assertFails(String expected, org.junit.jupiter.api.function.Executable executable) {
        PinfitException error = assertThrows(PinfitException.class, executable);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    @Test
    void forcedOverwriteOfAForeignFileReadsNoRegions() throws Exception {
        Path foreign = file("foreign.c", "int main(void) { return 0; }\n");
        assertEquals(0, tags.readForGeneration(foreign, true).regionCount());
        assertFails("Refusing to overwrite non-Pinfit file", () -> tags.readForGeneration(foreign, false));
    }

    @Test
    void unreadableFileIsReported() throws Exception {
        Path broken = temporaryDirectory.resolve("broken.c");
        Files.write(broken, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertFails("Cannot read " + broken, () -> tags.readForGeneration(broken, false));
        assertFails("Cannot strip tags from " + broken, () -> tags.stripTags(broken));
    }

    @Test
    void filesTooShortForAStoredHashSkipTheSkeletonCheck() throws Exception {
        assertEquals(0, tags.readForGeneration(file("one.c", MARKER), false).regionCount());
        assertEquals(0, tags.readForGeneration(file("two.c", MARKER + "\nint x;"), false).regionCount());
    }

    @Test
    void malformedRegionsAreRejected() throws Exception {
        String begin = "/*@Pinfit usercode+ a*/\n";
        String end = "/*@Pinfit usercode-*/\n";
        assertFails("Invalid or duplicate user region",
                () -> tags.readForGeneration(file("dup.c", MARKER + "\n" + begin + end + begin + end), false));
        assertFails("Unexpected end of user region",
                () -> tags.readForGeneration(file("end.c", MARKER + "\n" + end), false));
        assertFails("Unclosed user region 'a'",
                () -> tags.readForGeneration(file("open.c", MARKER + "\n" + begin), false));
    }

    @Test
    void writesCrlfAndLeavesUnmarkedContentWithoutAHash() throws Exception {
        Path crlf = temporaryDirectory.resolve("crlf.c");
        tags.writeGenerated(crlf, MARKER + "\nint x;\n", "\r\n");
        String written = Files.readString(crlf);
        assertTrue(written.startsWith(MARKER + "\r\n/*@Pinfit(skeleton-hash:"), written);
        assertTrue(written.endsWith("int x;\r\n"), written);

        Path singleLine = temporaryDirectory.resolve("single.c");
        tags.writeGenerated(singleLine, MARKER, "\n");
        assertEquals(MARKER, Files.readString(singleLine));

        Path plain = temporaryDirectory.resolve("plain.c");
        tags.writeGenerated(plain, "int x;\nint y;\n", "\n");
        assertEquals("int x;\nint y;\n", Files.readString(plain));
    }

    @Test
    void writeIntoAFileInsteadOfADirectoryFails() throws Exception {
        Path notADirectory = file("blocker", "");
        assertFails("Cannot write ", () -> tags.writeGenerated(notADirectory.resolve("out.c"), MARKER + "\n", "\n"));
    }

    @Test
    void stripTagsKeepsCrlfAndReportsUnchangedFiles() throws Exception {
        Path crlf = file("crlf.c", MARKER + "\r\nint x;\r\n");
        assertTrue(tags.stripTags(crlf));
        assertEquals("int x;\r\n", Files.readString(crlf));
        assertFalse(tags.stripTags(crlf));
    }

    @Test
    void userRegionsRenderAndForgetUnchangedBodies() throws Exception {
        Path source = file("regions.c", MARKER + "\n/*@Pinfit usercode+ a*/\nkept();\n/*@Pinfit usercode-*/\n");
        UserRegions regions = tags.readForGeneration(source, false);
        assertEquals("/*@Pinfit usercode+ b*/\nx();\n/*@Pinfit usercode-*/\n", regions.render("b", "x();\n"));
        regions.removeIfMatches("a", "different();");
        assertEquals(1, regions.regionCount());
        regions.removeIfMatches("a", "kept();");
        assertEquals(0, regions.regionCount());
    }

    @Test
    void markerRecognition() {
        assertFalse(PinfitTag.isGeneratedFile("/*@Pinfit(context:x)*/"));
        assertFalse(PinfitTag.isGeneratedFile("int x;"));
        assertEquals("a", PinfitTag.oldUserBeginName("/*@CGen(+a)*/"));
        assertNull(PinfitTag.oldUserBeginName("int x;"));
        assertEquals("a", PinfitTag.oldUserEndName("/*@CGen(-a)*/"));
        assertTrue(PinfitTag.isMarker("' /*@Pinfit(skeleton-hash:abc123)*/"));
        assertTrue(PinfitTag.isMarker("/*@Pinfit usercode-*/"));
        assertFalse(PinfitTag.isMarker("int x;"));
        assertEquals("r", PinfitTag.orphanedRegionName("/*@Pinfit(orphaned-user-region:r)*/"));
        assertNull(PinfitTag.orphanedRegionName("/*@Pinfit(context:x)*/"));
        assertNull(PinfitTag.skeletonHashValue("int x;"));
    }
}
