package dev.healstore.coordinator;

import static org.junit.jupiter.api.Assertions.*;

import dev.healstore.common.api.*;
import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.*;
import dev.healstore.coordinator.core.*;
import dev.healstore.coordinator.registry.NodeRegistry;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class ManifestTest {
    @Autowired ManifestService manifests;
    @Autowired NodeRegistry registry;
    @Autowired FailureDetector detector;
    @Autowired JdbcTemplate db;
    private static String encoded(int length) {
        return Base64.getEncoder().encodeToString(new byte[length]);
    }
    @Test
    void manifestsEnforceOwnershipImmutableChunksAndVerifiedCommit() {
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            registry.register(new RegisterNodeRequest(id, URI.create("https://node-" + id + ":8443")));
            detector.heartbeat(id);
        }
        UUID id = UUID.randomUUID();
        String owner = "a".repeat(64);
        var initial = new Header(1, id, encoded(16), encoded(12), encoded(48), 0, 0, "");
        manifests.create(initial, owner);
        manifests.owner(id, owner);
        assertThrows(SecurityException.class, () -> manifests.owner(id, "b".repeat(64)));
        List<Shard> shards = new ArrayList<>();
        for (int i = 0; i < 5; i++) shards.add(new Shard(UUID.randomUUID(), ShardCodec.hash(new byte[] {1})));
        var chunk = new Chunk(id, 0, 1, shards, encoded(32));
        var stored = manifests.plan(id, 0, chunk);
        assertEquals(5, stored.locations().stream().map(Location::nodeId).distinct().count());
        assertEquals(stored, manifests.plan(id, 0, chunk));
        var changed = new Chunk(id, 0, 2, shards, encoded(32));
        assertThrows(RuntimeException.class, () -> manifests.plan(id, 0, changed));
        var header =
            new Header(1, id, initial.salt(), initial.wrapNonce(), initial.wrappedKey(), 1, 1, encoded(32));
        assertThrows(IllegalStateException.class, () -> manifests.commit(header));
        UUID shard = shards.getFirst().id(), holder = stored.locations().getFirst().nodeId();
        assertEquals(chunk.shardBytes(), manifests.grant(shard, holder, owner).bytes());
        assertThrows(SecurityException.class, () -> manifests.grant(shard, holder, "b".repeat(64)));
        assertThrows(SecurityException.class, () -> manifests.grant(shard, UUID.randomUUID(), owner));
        db.update("UPDATE chunks SET verified=TRUE WHERE object_id=?", id);
        assertEquals(header, manifests.commit(header));
        assertEquals(header, manifests.read(id));
        assertThrows(RuntimeException.class, () -> manifests.plan(id, 0, chunk));
    }
}
