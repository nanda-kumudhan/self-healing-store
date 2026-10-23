package dev.healstore.coordinator.api;

import dev.healstore.common.api.*;
import dev.healstore.common.core.CertificateIdentity;
import dev.healstore.common.model.Manifest.*;
import dev.healstore.coordinator.core.*;
import dev.healstore.coordinator.registry.NodeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CoordinatorController {
    private final NodeRegistry registry;
    private final FailureDetector detector;
    private final RepairService repairs;
    private final ManifestService manifests;
    private final boolean loopback;
    public CoordinatorController(NodeRegistry registry, FailureDetector detector, RepairService repairs,
        ManifestService manifests, @Value("${store.allow-loopback-registration:false}") boolean loopback) {
        this.registry = registry;
        this.detector = detector;
        this.repairs = repairs;
        this.manifests = manifests;
        this.loopback = loopback;
    }
    private CertificateIdentity peer(HttpServletRequest r) {
        return CertificateIdentity.from(
            (X509Certificate[]) r.getAttribute("jakarta.servlet.request.X509Certificate"));
    }
    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return Capabilities.implemented("coordinator");
    }
    @GetMapping("/nodes")
    public List<NodeView> nodes() {
        return registry.list();
    }
    @GetMapping("/nodes/{id}")
    public NodeView node(@PathVariable UUID id) {
        return registry.get(id);
    }
    @PostMapping("/nodes")
    public ResponseEntity<NodeView> register(
        @Valid @RequestBody RegisterNodeRequest body, HttpServletRequest r) {
        var p = peer(r);
        if (!p.nodeId().equals(body.id()))
            throw new SecurityException("Certificate does not match node ID");
        String host = body.endpoint().getHost();
        if (!(p.name().equals(host) || (loopback && ("localhost".equals(host) || "127.0.0.1".equals(host))))
            || !(body.endpoint().getPath().isEmpty() || body.endpoint().getPath().equals("/")))
            throw new SecurityException("Node endpoint must match its certificate name");
        NodeView result;
        try {
            result = registry.get(body.id());
            if (!result.endpoint().equals(body.endpoint()))
                throw new SecurityException("Registered endpoint is immutable");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode().value() != 404)
                throw e;
            result = registry.register(body);
        }
        detector.heartbeat(body.id());
        return ResponseEntity.created(URI.create("/api/v1/nodes/" + body.id())).body(result);
    }
    @PostMapping("/nodes/{id}/heartbeats")
    public void heartbeat(@PathVariable UUID id, HttpServletRequest r) {
        if (!peer(r).nodeId().equals(id))
            throw new SecurityException("Cannot heartbeat another node");
        detector.heartbeat(id);
    }
    @PostMapping("/nodes/{id}/damage/{shard}")
    public void damage(@PathVariable UUID id, @PathVariable UUID shard, HttpServletRequest r) {
        if (!peer(r).nodeId().equals(id))
            throw new SecurityException("Cannot report as another node");
        manifests.damage(shard, id);
    }
    @GetMapping("/shards/{id}/grant")
    public Grant grant(@PathVariable UUID id, @RequestParam String caller, HttpServletRequest r) {
        return manifests.grant(id, peer(r).nodeId(), caller);
    }
    @PostMapping("/uploads")
    public Header upload(@RequestBody Header header, HttpServletRequest r) {
        return manifests.create(header, peer(r).fingerprint());
    }
    @PutMapping("/objects/{id}/chunks/{index}")
    public StoredChunk plan(
        @PathVariable UUID id, @PathVariable int index, @RequestBody Chunk chunk, HttpServletRequest r) {
        manifests.owner(id, peer(r).fingerprint());
        return manifests.plan(id, index, chunk);
    }
    @PostMapping("/objects/{id}/chunks/{index}/commit")
    public void verify(@PathVariable UUID id, @PathVariable int index, HttpServletRequest r)
        throws Exception {
        manifests.owner(id, peer(r).fingerprint());
        repairs.verifyUpload(manifests.chunk(id, index));
        manifests.database().update(
            "UPDATE chunks SET verified=TRUE WHERE object_id=? AND chunk_index=?", id, index);
    }
    @PostMapping("/objects/{id}/commit")
    public Header commit(@PathVariable UUID id, @RequestBody Header header, HttpServletRequest r) {
        manifests.owner(id, peer(r).fingerprint());
        if (!id.equals(header.objectId()))
            throw new IllegalArgumentException("Wrong object ID");
        return manifests.commit(header);
    }
    @GetMapping("/objects/{id}")
    public Header object(@PathVariable UUID id, HttpServletRequest r) {
        manifests.owner(id, peer(r).fingerprint());
        return manifests.read(id);
    }
    @GetMapping("/objects/{id}/chunks/{index}")
    public StoredChunk chunk(@PathVariable UUID id, @PathVariable int index, HttpServletRequest r) {
        manifests.owner(id, peer(r).fingerprint());
        return manifests.chunk(id, index);
    }
    @PostMapping("/objects/{id}/repairs")
    public RepairResult repair(@PathVariable UUID id, HttpServletRequest r) {
        manifests.owner(id, peer(r).fingerprint());
        return repairs.request(id);
    }
}
