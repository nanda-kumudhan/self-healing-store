package dev.healstore.node.core;

import dev.healstore.common.io.Json;
import dev.healstore.common.model.Manifest.Grant;
import dev.healstore.node.storage.OpaqueShardStore;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Scrubber {
    private final OpaqueShardStore store;
    private final ShardIntegrityPolicy integrity;
    private final NodeControl control;
    public Scrubber(OpaqueShardStore store, ShardIntegrityPolicy integrity, NodeControl control) {
        this.store = store;
        this.integrity = integrity;
        this.control = control;
    }
    public synchronized int request() throws Exception {
        int damaged = 0;
        // Sidecars also let me find bytes that disappeared from an otherwise live node.
        try (var entries = Files.newDirectoryStream(store.directory(), "*.hash")) {
            for (Path metadata : entries) {
                UUID id = UUID.fromString(metadata.getFileName().toString().replace(".hash", ""));
                boolean corrupt = false;
                try {
                    integrity.verify(store.get(id), Json.read(Files.readString(metadata), Grant.class));
                } catch (Exception e) {
                    corrupt = true;
                }
                if (corrupt) {
                    control.damage(id);
                    damaged++;
                }
            }
        }
        try (var entries = Files.newDirectoryStream(store.directory(), "*.shard")) {
            for (Path path : entries) {
                UUID id = UUID.fromString(path.getFileName().toString().replace(".shard", ""));
                if (!Files.exists(store.metadata(id))) {
                    control.damage(id);
                    damaged++;
                }
            }
        }
        return damaged;
    }
    @Scheduled(fixedDelayString = "${store.scrub-ms:30000}", initialDelay = 30000)
    public void scheduled() {
        try {
            int n = request();
            if (n > 0)
                org.slf4j.LoggerFactory.getLogger(Scrubber.class).warn("Scrub found {} damaged shards", n);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(Scrubber.class)
                .warn("Scrub failed; will retry: {}", e.toString());
        }
    }
}
