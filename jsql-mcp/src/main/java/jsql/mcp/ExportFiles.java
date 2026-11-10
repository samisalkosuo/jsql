package jsql.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jsql.core.InvalidRequestException;
import jsql.core.ListResult;

/**
 * Safe access to the export directory: path checks and the list, read and delete operations.
 *
 * <p>
 * A path is accepted only if it is relative and stays inside the export directory after
 * {@code ..} segments and symbolic links are resolved.
 */
final class ExportFiles {

    static final String OUTSIDE_MESSAGE = "Path is outside the export directory";
    static final int MAX_CHUNK_BYTES = 256 * 1024;
    static final int MAX_LIST_ENTRIES = 1000;

    private ExportFiles() {
    }

    /** One file in the export directory. {@code path} is relative to it, with '/' separators. */
    record ExportFile(String path, long size, Instant modified) {
    }

    /** A piece of a file. {@code nextOffset} is where the next call should start. */
    record Chunk(String path, long offset, String text, long nextOffset, boolean eof, long size) {
    }

    static Path root(String exportDir) {
        return Paths.get(exportDir).toAbsolutePath().normalize();
    }

    /**
     * Resolves a path given by a client against the export directory.
     *
     * @throws InvalidRequestException if the path is empty, absolute or leaves the directory
     */
    static Path resolve(String exportDir, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new InvalidRequestException("Path is required.");
        }
        if (relative.indexOf('\0') >= 0 || relative.startsWith("/") || relative.startsWith("\\")) {
            throw outside(relative);
        }
        Path root = root(exportDir);
        Path candidate;
        try {
            Path given = Paths.get(relative);
            if (given.isAbsolute() || given.getRoot() != null) {
                throw outside(relative);
            }
            candidate = root.resolve(given).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new InvalidRequestException("Invalid path: " + relative);
        }
        if (!candidate.startsWith(root)) {
            throw outside(relative);
        }
        // symbolic links: the real location of the deepest existing part must be inside the real root
        try {
            Path realRoot = Files.exists(root) ? root.toRealPath() : root;
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
                throw outside(relative);
            }
        } catch (IOException e) {
            throw new InvalidRequestException("Cannot check path: " + relative);
        }
        return candidate;
    }

    private static InvalidRequestException outside(String relative) {
        return new InvalidRequestException(OUTSIDE_MESSAGE + ": " + relative);
    }

    /** Lists regular files below {@code subDir} (the whole directory if null), sorted by path. */
    static ListResult<ExportFile> list(String exportDir, String subDir) {
        Path root = root(exportDir);
        Path start = subDir == null || subDir.isBlank() ? root : resolve(exportDir, subDir);
        if (!Files.isDirectory(start)) {
            return new ListResult<>(List.of(), false);
        }
        List<ExportFile> files = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(start)) {
            List<Path> paths = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path p : paths) {
                if (files.size() >= MAX_LIST_ENTRIES) {
                    truncated = true;
                    break;
                }
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                files.add(new ExportFile(root.relativize(p).toString().replace('\\', '/'), attrs.size(),
                        attrs.lastModifiedTime().toInstant()));
            }
        } catch (IOException | UncheckedIOException e) {
            throw new InvalidRequestException("Cannot list the export directory: " + e.getMessage());
        }
        return new ListResult<>(files, truncated);
    }

    /**
     * Reads at most {@code limitBytes} (and never more than 256 KB) from {@code offset}. The text
     * ends on a character boundary; {@code nextOffset} accounts for that.
     */
    static Chunk read(String exportDir, String path, Long offset, Integer limitBytes) {
        Path file = resolve(exportDir, path);
        long start = offset == null ? 0 : offset;
        if (start < 0) {
            throw new InvalidRequestException("offset must not be negative.");
        }
        int limit = limitBytes == null ? MAX_CHUNK_BYTES : limitBytes;
        if (limit < 1) {
            throw new InvalidRequestException("limit_bytes must be at least 1.");
        }
        limit = Math.min(limit, MAX_CHUNK_BYTES);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidRequestException("File not found: " + path);
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            if (start >= size) {
                return new Chunk(path, start, "", start, true, size);
            }
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(limit, size - start));
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // keep reading
            }
            int length = buf.position();
            boolean eof = start + length >= size;
            int usable = eof ? length : utf8Boundary(buf.array(), length);
            if (usable == 0 && length > 0) {
                usable = length; // a single character larger than the limit: do not stall
            }
            String text = new String(buf.array(), 0, usable, StandardCharsets.UTF_8);
            long next = start + usable;
            return new Chunk(path, start, text, next, next >= size, size);
        } catch (IOException e) {
            throw new InvalidRequestException("Cannot read file: " + e.getMessage());
        }
    }

    // Length of the prefix that does not end in the middle of a UTF-8 sequence.
    private static int utf8Boundary(byte[] bytes, int length) {
        int i = length;
        int back = 0;
        while (i > 0 && back < 4 && (bytes[i - 1] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i == 0) {
            return length;
        }
        int lead = bytes[i - 1] & 0xFF;
        int needed = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        int have = back + 1;
        return have >= needed ? length : i - 1;
    }

    /** Deletes one file. Returns false if it did not exist. */
    static boolean delete(String exportDir, String path) {
        Path file = resolve(exportDir, path);
        if (file.equals(root(exportDir))) {
            throw outside(path);
        }
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidRequestException("Not a file: " + path);
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new InvalidRequestException("Cannot delete file: " + e.getMessage());
        }
    }
}
