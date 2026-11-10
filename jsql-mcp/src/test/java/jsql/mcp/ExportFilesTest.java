package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import jsql.core.InvalidRequestException;
import jsql.mcp.ExportFiles.Chunk;

public class ExportFilesTest {

    private Path temp;
    private String exportDir;

    @Before
    public void setUp() throws IOException {
        temp = Files.createTempDirectory("jsql-exportfiles");
        Path export = Files.createDirectories(temp.resolve("export"));
        exportDir = export.toString();
    }

    @After
    public void tearDown() throws IOException {
        try (var walk = Files.walk(temp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort
                }
            });
        }
    }

    private void assertOutside(String path) {
        try {
            ExportFiles.resolve(exportDir, path);
            fail("accepted " + path);
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Path is outside the export directory"));
        }
    }

    @Test
    public void relativePathInsideIsAccepted() {
        Path p = ExportFiles.resolve(exportDir, "sub/data.csv");
        assertTrue(p.startsWith(ExportFiles.root(exportDir)));
    }

    @Test
    public void dotDotEscapeIsRejected() {
        assertOutside("../secret.csv");
        assertOutside("sub/../../secret.csv");
        assertOutside("..\\secret.csv");
    }

    @Test
    public void absolutePathIsRejected() {
        assertOutside(temp.resolve("other.csv").toAbsolutePath().toString());
        assertOutside("/etc/passwd");
        assertOutside("\\windows\\win.ini");
    }

    @Test
    public void symlinkEscapeIsRejected() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = Path.of(exportDir).resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assume.assumeNoException("symbolic links are not available here", e);
        }
        assertOutside("link/secret.txt");
        assertOutside("link");
        try {
            ExportFiles.read(exportDir, "link/secret.txt", null, null);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().startsWith("Path is outside the export directory"));
        }
        assertTrue("the file outside was not touched", Files.exists(outside.resolve("secret.txt")));
    }

    @Test
    public void emptyPathIsRejected() {
        try {
            ExportFiles.resolve(exportDir, " ");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("required"));
        }
    }

    @Test
    public void listShowsFilesWithSizeAndSubDirectories() throws IOException {
        Files.writeString(Path.of(exportDir).resolve("a.csv"), "1,2\n");
        Files.createDirectories(Path.of(exportDir).resolve("sub"));
        Files.writeString(Path.of(exportDir).resolve("sub").resolve("b.csv"), "xyz");

        var all = ExportFiles.list(exportDir, null);
        assertFalse(all.truncated());
        assertEquals(2, all.items().size());
        assertEquals("a.csv", all.items().get(0).path());
        assertEquals(4, all.items().get(0).size());
        assertEquals("sub/b.csv", all.items().get(1).path());

        var sub = ExportFiles.list(exportDir, "sub");
        assertEquals(1, sub.items().size());
        assertTrue(ExportFiles.list(exportDir, "missing").items().isEmpty());
    }

    @Test
    public void readInChunksUntilEof() throws IOException {
        String content = "abcdefghij".repeat(100); // 1000 bytes
        Files.writeString(Path.of(exportDir).resolve("big.txt"), content);

        Chunk first = ExportFiles.read(exportDir, "big.txt", 0L, 400);
        assertEquals(400, first.text().length());
        assertEquals(400, first.nextOffset());
        assertFalse(first.eof());
        assertEquals(1000, first.size());

        Chunk second = ExportFiles.read(exportDir, "big.txt", first.nextOffset(), 400);
        assertEquals(800, second.nextOffset());
        assertFalse(second.eof());

        Chunk third = ExportFiles.read(exportDir, "big.txt", second.nextOffset(), 400);
        assertEquals(200, third.text().length());
        assertTrue(third.eof());
        assertEquals(content, first.text() + second.text() + third.text());

        Chunk past = ExportFiles.read(exportDir, "big.txt", 5000L, null);
        assertEquals("", past.text());
        assertTrue(past.eof());
    }

    @Test
    public void chunkSizeIsCappedAt256Kb() throws IOException {
        Files.write(Path.of(exportDir).resolve("large.txt"), "x".repeat(300_000).getBytes(StandardCharsets.UTF_8));
        Chunk c = ExportFiles.read(exportDir, "large.txt", null, 10_000_000);
        assertEquals(262_144, c.text().length());
        assertEquals(262_144, c.nextOffset());
        assertFalse(c.eof());
    }

    @Test
    public void chunksNeverSplitAMultiByteCharacter() throws IOException {
        String content = "\u00e4".repeat(50); // 2 bytes each, 100 bytes
        Files.writeString(Path.of(exportDir).resolve("utf8.txt"), content);
        StringBuilder all = new StringBuilder();
        long offset = 0;
        for (int guard = 0; guard < 100; guard++) {
            Chunk c = ExportFiles.read(exportDir, "utf8.txt", offset, 7);
            all.append(c.text());
            offset = c.nextOffset();
            if (c.eof()) {
                break;
            }
        }
        assertEquals(content, all.toString());
    }

    @Test
    public void readOfMissingFileAndBadOffsetFail() {
        try {
            ExportFiles.read(exportDir, "nope.txt", null, null);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
        try {
            ExportFiles.read(exportDir, "nope.txt", -1L, null);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("offset"));
        }
    }

    @Test
    public void deleteRemovesFileAndReportsMissing() throws IOException {
        Path f = Path.of(exportDir).resolve("gone.csv");
        Files.writeString(f, "x");
        assertTrue(ExportFiles.delete(exportDir, "gone.csv"));
        assertFalse(Files.exists(f));
        assertFalse(ExportFiles.delete(exportDir, "gone.csv"));
        try {
            ExportFiles.delete(exportDir, "../x");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().startsWith("Path is outside the export directory"));
        }
    }
}
