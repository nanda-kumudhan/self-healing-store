package dev.healstore.coordinator;

import static org.junit.jupiter.api.Assertions.*;

import dev.healstore.common.api.*;
import dev.healstore.coordinator.core.*;
import dev.healstore.coordinator.registry.NodeRegistry;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class DesignTest {
    @Autowired JdbcTemplate db;
    @Autowired NodeRegistry registry;
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        public Clock withZone(ZoneId z) {
            return this;
        }
        public Instant instant() {
            return now;
        }
    }
    @Test
    void heartbeatTimeoutAndRepairDelayHaveExactBoundariesAndRestartGrace() {
        UUID id = UUID.randomUUID();
        registry.register(new RegisterNodeRequest(id, URI.create("https://node-" + id + ":8443")));
        var clock = new MutableClock();
        var detector = new FailureDetector(db, clock, Duration.ofSeconds(10), Duration.ofSeconds(20));
        detector.heartbeat(id);
        assertTrue(detector.live(id));
        clock.now = clock.now.plusSeconds(10);
        assertFalse(detector.live(id));
        assertFalse(detector.repairable(id));
        clock.now = clock.now.plusSeconds(19);
        assertFalse(detector.repairable(id));
        clock.now = clock.now.plusSeconds(1);
        assertTrue(detector.repairable(id));
        var restarted = new FailureDetector(db, clock, Duration.ofSeconds(10), Duration.ofSeconds(20));
        assertFalse(restarted.repairable(id));
        detector.heartbeat(id);
        assertTrue(detector.live(id));
        assertFalse(detector.repairable(id));
    }
    @Test
    void rendezvousIsStableDistinctAndAddingANodePreservesExistingRelativeOrder() {
        var nodes = new ArrayList<NodeView>();
        for (int i = 0; i < 6; i++)
            nodes.add(new NodeView(UUID.randomUUID(), URI.create("https://node-" + i), Instant.EPOCH));
        var first = PlacementPolicy.rank("chunk", nodes);
        Collections.reverse(nodes);
        assertEquals(first, PlacementPolicy.rank("chunk", nodes));
        NodeView extra = new NodeView(UUID.randomUUID(), URI.create("https://extra"), Instant.EPOCH);
        nodes.add(extra);
        assertEquals(
            first, PlacementPolicy.rank("chunk", nodes).stream().filter(n -> !n.equals(extra)).toList());
        assertEquals(6, first.stream().map(NodeView::id).distinct().count());
    }
}
