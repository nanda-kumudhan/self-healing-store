package dev.healstore.coordinator.core;

import dev.healstore.common.api.NodeView;
import dev.healstore.common.io.ShardCodec;
import dev.healstore.coordinator.registry.NodeRegistry;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PlacementPolicy {
    private final NodeRegistry registry;
    private final FailureDetector detector;
    public PlacementPolicy(NodeRegistry registry, FailureDetector detector) {
        this.registry = registry;
        this.detector = detector;
    }
    public List<NodeView> rank(String key, Set<UUID> excluded) {
        return rank(key,
            registry.list()
                .stream()
                .filter(n -> detector.live(n.id()) && !excluded.contains(n.id()))
                .toList());
    }
    public static List<NodeView> rank(String key, List<NodeView> nodes) {
        return nodes.stream()
            .sorted(Comparator
                    .<NodeView, String>comparing(n
                        -> ShardCodec.hash(
                            (key + "|" + n.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .reversed()
                    .thenComparing(n -> n.id().toString()))
            .toList();
    }
    public List<NodeView> planUpload(String key) {
        var nodes = rank(key, Set.of());
        if (nodes.size() < 5)
            throw new IllegalStateException("Five distinct live nodes are required for upload");
        return nodes.subList(0, 5);
    }
}
