package dev.healstore.node.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.Grant;
import dev.healstore.node.storage.OpaqueShardStore;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrityTest {
    @TempDir Path dir;
    @Test
    void scrubFindsBothCorruptedAndDeletedShards() throws Exception {
        var store = new OpaqueShardStore(dir, 1024);
        var policy = new ShardIntegrityPolicy();
        var control = mock(NodeControl.class);
        UUID corrupt = UUID.randomUUID(), missing = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        var grant = new Grant(ShardCodec.hash(bytes), bytes.length);
        for (UUID id : new UUID[] {corrupt, missing}) {
            store.put(
                id, new java.io.ByteArrayInputStream(bytes), bytes.length, p -> policy.verify(p, grant));
            Files.writeString(store.metadata(id), Json.write(grant));
        }
        Files.write(store.get(corrupt), new byte[] {9, 2, 3});
        Files.delete(store.get(missing));
        assertEquals(2, new Scrubber(store, policy, control).request());
        verify(control).damage(corrupt);
        verify(control).damage(missing);
    }
    @Test
    void anUntrustedBodyCannotPublishEvenWithCorrectLength() throws Exception {
        var store = new OpaqueShardStore(dir, 1024);
        var policy = new ShardIntegrityPolicy();
        UUID id = UUID.randomUUID();
        assertThrows(SecurityException.class,
            ()
                -> store.put(id, new java.io.ByteArrayInputStream(new byte[] {9}), 1,
                    p -> policy.verify(p, new Grant(ShardCodec.hash(new byte[] {1}), 1))));
        assertThrows(java.io.FileNotFoundException.class, () -> store.get(id));
        try (var paths = Files.list(dir)) {
            assertEquals(0, paths.count());
        }
    }
}
