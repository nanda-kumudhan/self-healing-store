package dev.healstore.node.api;

import dev.healstore.common.api.Capabilities;
import dev.healstore.node.core.Scrubber;
import dev.healstore.node.core.ShardIntegrityPolicy;
import dev.healstore.node.storage.OpaqueShardStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StorageController {
    private final OpaqueShardStore store;
    private final ShardIntegrityPolicy integrity;
    private final Scrubber scrubber;

    public StorageController(OpaqueShardStore store, ShardIntegrityPolicy integrity, Scrubber scrubber) {
        this.store = store; this.integrity = integrity; this.scrubber = scrubber;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() { return Capabilities.scaffold("storage-node"); }

    @PutMapping(value = "/shards/{shardId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> put(@PathVariable UUID shardId, HttpServletRequest request) throws IOException {
        store.put(shardId, request.getInputStream(), request.getContentLengthLong(), integrity::verify);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/shards/{shardId}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<FileSystemResource> get(@PathVariable UUID shardId) throws IOException {
        var path = store.get(shardId);
        integrity.verify(path);
        var resource = new FileSystemResource(path);
        return ResponseEntity.ok().contentLength(resource.contentLength())
            .contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
    }

    @PostMapping("/scrubs")
    public void scrub() { scrubber.request(); }
}
