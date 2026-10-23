package dev.healstore.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.healstore.client.core.ClientFileOperations;
import dev.healstore.common.model.Manifest;
import java.nio.file.*;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingFileTest {
    @TempDir Path dir;
    @Test
    void diskRoundTripsIncludeEmptyAndAllChunkBoundaries() throws Exception {
        for (int size : new int[] {0, 1, Manifest.CHUNK_BYTES - 1, Manifest.CHUNK_BYTES,
                 Manifest.CHUNK_BYTES + 1, 3 * Manifest.CHUNK_BYTES + 37}) {
            byte[] bytes = new byte[size];
            new Random(size).nextBytes(bytes);
            Path src = dir.resolve("source"), dest = dir.resolve("out-" + size);
            Files.write(src, bytes);
            new ClientFileOperations().localRoundTrip(src, dest);
            assertArrayEquals(bytes, Files.readAllBytes(dest));
        }
    }
    @Test
    void localRoundTripRefusesToOverwrite() throws Exception {
        Path src = dir.resolve("source"), dest = dir.resolve("out");
        Files.writeString(src, "data");
        Files.writeString(dest, "keep");
        assertThrows(
            FileAlreadyExistsException.class, () -> new ClientFileOperations().localRoundTrip(src, dest));
        assertEquals("keep", Files.readString(dest));
    }
}
