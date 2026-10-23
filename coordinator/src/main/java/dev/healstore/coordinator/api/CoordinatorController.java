package dev.healstore.coordinator.api;

import dev.healstore.common.api.Capabilities;
import dev.healstore.common.api.NodeView;
import dev.healstore.common.api.RegisterNodeRequest;
import dev.healstore.coordinator.core.*;
import dev.healstore.coordinator.registry.NodeRegistry;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CoordinatorController {
    private final NodeRegistry registry;
    private final PlacementPolicy placement;
    private final FailureDetector failureDetector;
    private final RepairService repairs;
    private final ManifestService manifests;

    public CoordinatorController(NodeRegistry registry, PlacementPolicy placement,
            FailureDetector failureDetector, RepairService repairs, ManifestService manifests) {
        this.registry = registry; this.placement = placement; this.failureDetector = failureDetector;
        this.repairs = repairs; this.manifests = manifests;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() { return Capabilities.scaffold("coordinator"); }

    @GetMapping("/nodes")
    public List<NodeView> nodes() { return registry.list(); }

    @GetMapping("/nodes/{nodeId}")
    public NodeView node(@PathVariable UUID nodeId) { return registry.get(nodeId); }

    @PostMapping("/nodes")
    public ResponseEntity<NodeView> register(@Valid @RequestBody RegisterNodeRequest request) {
        var node = registry.register(request);
        return ResponseEntity.created(URI.create("/api/v1/nodes/" + node.id())).body(node);
    }

    @PostMapping("/nodes/{nodeId}/heartbeats")
    public void heartbeat(@PathVariable UUID nodeId) { failureDetector.heartbeat(nodeId); }

    @PostMapping("/uploads")
    public void upload() { placement.planUpload(); }

    @GetMapping("/objects/{objectId}")
    public void object(@PathVariable UUID objectId) { manifests.read(objectId); }

    @PostMapping("/objects/{objectId}/repairs")
    public void repair(@PathVariable UUID objectId) { repairs.request(objectId); }
}
