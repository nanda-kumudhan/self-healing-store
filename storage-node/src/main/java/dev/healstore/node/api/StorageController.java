package dev.healstore.node.api;

import dev.healstore.common.api.Capabilities;
import dev.healstore.common.core.CertificateIdentity;
import dev.healstore.common.io.Json;
import dev.healstore.node.core.*;
import dev.healstore.node.storage.OpaqueShardStore;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StorageController {
    private final OpaqueShardStore store;
    private final ShardIntegrityPolicy integrity;
    private final Scrubber scrubber;
    private final NodeControl control;
    public StorageController(
        OpaqueShardStore store, ShardIntegrityPolicy integrity, Scrubber scrubber, NodeControl control) {
        this.store = store;
        this.integrity = integrity;
        this.scrubber = scrubber;
        this.control = control;
    }
    private String caller(HttpServletRequest r) {
        var p = CertificateIdentity.from(
            (X509Certificate[]) r.getAttribute("jakarta.servlet.request.X509Certificate"));
        return p.coordinator() ? "coordinator" : p.fingerprint();
    }
    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return Capabilities.implemented("storage-node");
    }
    @PutMapping(value = "/shards/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> put(@PathVariable UUID id, HttpServletRequest request) throws Exception {
        var grant = control.grant(id, caller(request));
        if (request.getContentLengthLong() != grant.bytes())
            throw new IllegalArgumentException("Expected reserved shard length");
        store.put(id, request.getInputStream(), grant.bytes(), path -> integrity.verify(path, grant));
        Path temp = Files.createTempFile(store.directory(), ".hash-", ".part");
        try {
            Files.writeString(temp, Json.write(grant));
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, store.metadata(id), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            store.syncDirectory();
        } finally {
            Files.deleteIfExists(temp);
        }
        return ResponseEntity.noContent().build();
    }
    @GetMapping(value = "/shards/{id}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<FileSystemResource> get(@PathVariable UUID id, HttpServletRequest request)
        throws Exception {
        var grant = control.grant(id, caller(request));
        var path = store.get(id);
        try {
            integrity.verify(path, grant);
        } catch (SecurityException e) {
            control.damage(id);
            throw e;
        }
        // Rebuild a missing cache entry after a crash between publishing bytes and their sidecar.
        if (!Files.exists(store.metadata(id)))
            Files.writeString(store.metadata(id), Json.write(grant), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        var resource = new FileSystemResource(path);
        return ResponseEntity.ok()
            .contentLength(grant.bytes())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(resource);
    }
    @PostMapping("/scrubs")
    public Map<String, Integer> scrub() throws Exception {
        return Map.of("damagedShards", scrubber.request());
    }
}
