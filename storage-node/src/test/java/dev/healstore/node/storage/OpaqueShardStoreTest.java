package dev.healstore.node.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpaqueShardStoreTest {
    @TempDir Path directory;

    @Test void writesOpaqueBytesWithoutOverwritingExistingShard() throws Exception {
        var store = new OpaqueShardStore(directory, 1024); UUID id = UUID.randomUUID();
        store.put(id, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, ignored -> {});
        assertThrows(FileAlreadyExistsException.class,
            () -> store.put(id, new ByteArrayInputStream(new byte[]{4}), 1, ignored -> {}));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(store.get(id)));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void oversizedUnknownLengthBodyLeavesNoPartialShard() throws Exception {
        var store = new OpaqueShardStore(directory, 2);
        assertThrows(ShardTooLargeException.class, () -> store.put(UUID.randomUUID(),
            new ByteArrayInputStream(new byte[]{1, 2, 3}), -1, ignored -> {}));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void failedIntegrityHookLeavesNoPublishedShard() throws Exception {
        var store = new OpaqueShardStore(directory, 1024);
        assertThrows(IllegalArgumentException.class, () -> store.put(UUID.randomUUID(),
            new ByteArrayInputStream(new byte[]{1}), 1, ignored -> { throw new IllegalArgumentException("invalid"); }));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void interruptedStreamLeavesNoPartialShard() throws Exception {
        var store = new OpaqueShardStore(directory, 1024);
        assertThrows(IOException.class, () -> store.put(UUID.randomUUID(), new InputStream() {
            @Override public int read() throws IOException { throw new IOException("disconnected"); }
        }, -1, ignored -> {}));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void streamingUsesBoundedReadRequests() throws Exception {
        var store = new OpaqueShardStore(directory, 4 * 1024 * 1024);
        try (var input = new InputStream() {
            int remaining = 4 * 1024 * 1024;
            @Override public int read() { throw new AssertionError("Use bulk reads"); }
            @Override public int read(byte[] buffer, int offset, int length) {
                assertTrue(length <= 64 * 1024);
                if (remaining == 0) return -1;
                int count = Math.min(remaining, length); remaining -= count; return count;
            }
        }) {
            UUID id = UUID.randomUUID(); store.put(id, input, -1, ignored -> {});
            assertEquals(4 * 1024 * 1024, Files.size(store.get(id)));
        }
    }

    @Test void concurrentSameIdPublicationHasExactlyOneWinner() throws Exception {
        var store = new OpaqueShardStore(directory, 1024); UUID id = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var work = (java.util.concurrent.Callable<Boolean>) () -> {
                try { store.put(id, new ByteArrayInputStream(new byte[]{1}), 1, ignored -> {}); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            var first = executor.submit(work); var second = executor.submit(work);
            assertNotEquals(first.get(), second.get());
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
