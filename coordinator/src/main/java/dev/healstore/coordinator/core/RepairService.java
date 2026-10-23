package dev.healstore.coordinator.core;

import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One worker caps aggregate repair traffic. The durable queue is the damaged assignment set. */
@Component
public class RepairService {
    private final ManifestService manifests;
    private final PlacementPolicy placement;
    private final FailureDetector detector;
    private final HttpTransport http;
    private final long bytesPerSecond;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RepairService.class);
    public RepairService(ManifestService manifests, PlacementPolicy placement, FailureDetector detector,
        @Value("${store.repair-bytes-per-second:10485760}") long bandwidth) throws Exception {
        if (bandwidth <= 0)
            throw new IllegalArgumentException("Repair bandwidth must be positive");
        this.manifests = manifests;
        this.placement = placement;
        this.detector = detector;
        bytesPerSecond = bandwidth;
        http = HttpTransport.service();
    }
    public void verifyUpload(StoredChunk stored) throws Exception {
        for (int i = 0; i < 5; i++) {
            byte[] bytes = fetch(stored, i);
            if (bytes.length != stored.chunk().shardBytes()
                || !ShardCodec.hash(bytes).equals(stored.chunk().shards().get(i).hash()))
                throw new IllegalStateException("Uploaded shard failed verification");
        }
    }
    private java.net.URI route(java.net.URI endpoint) {
        String host = System.getenv("STORE_NODE_PROXY_HOST");
        if (host == null || !endpoint.getHost().matches("node-[1-9][0-9]*"))
            return endpoint;
        return java.net.URI.create(
            "https://" + host + ":" + (18443 + Integer.parseInt(endpoint.getHost().substring(5))));
    }
    private byte[] fetch(StoredChunk stored, int i) throws Exception {
        return http.send(route(stored.locations().get(i).endpoint()), "GET",
            "/api/v1/shards/" + stored.chunk().shards().get(i).id(), null, "application/octet-stream");
    }
    public synchronized RepairResult request(UUID objectId) {
        long start = System.nanoTime();
        int repaired = 0, pending = 0;
        Header header = manifests.read(objectId);
        // Manual repair checks every chunk, including damage not yet found by a scrubber.
        for (int index = 0; index < header.chunks(); index++) {
            int[] result = repair(manifests.chunk(objectId, index));
            repaired += result[0];
            pending += result[1];
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        LOG.info("repair object={} repaired={} pending={} elapsed_seconds={}", objectId, repaired, pending,
            seconds);
        return new RepairResult(repaired, pending, seconds);
    }
    private int[] repair(StoredChunk stored) {
        long started = System.nanoTime(), transferred = 0;
        var c = stored.chunk();
        byte[][] shards = new byte[5][];
        boolean[] valid = new boolean[5];
        int missing = 0, repaired = 0;
        for (int i = 0; i < 5; i++) {
            try {
                shards[i] = fetch(stored, i);
                transferred += shards[i].length;
                throttle(started, transferred);
                valid[i] = shards[i].length == c.shardBytes()
                    && ShardCodec.hash(shards[i]).equals(c.shards().get(i).hash());
            } catch (Exception e) {
                valid[i] = false;
            }
            if (!valid[i]) {
                missing++;
                manifests.damage(c.shards().get(i).id(), stored.locations().get(i).nodeId());
            }
        }
        if (missing == 0) {
            manifests.database().update(
                "UPDATE shard_assignments SET damaged=FALSE WHERE object_id=? AND chunk_index=?",
                c.objectId(), c.index());
            return new int[] {0, 0};
        }
        if (missing > 2)
            return new int[] {0, missing};
        ShardCodec.recover(shards, valid, c.shardBytes());
        Set<UUID> occupied = new HashSet<>();
        stored.locations().forEach(l -> occupied.add(l.nodeId()));
        for (int i = 0; i < 5; i++)
            if (!valid[i]) {
                if (!ShardCodec.hash(shards[i]).equals(c.shards().get(i).hash()))
                    return new int[] {repaired, missing - repaired};
                var candidates = placement.rank(c.objectId() + ":" + c.index(), occupied);
                for (var target : candidates) {
                    UUID shard = c.shards().get(i).id();
                    UUID old = stored.locations().get(i).nodeId();
                    manifests.database().update(
                        "UPDATE shard_assignments SET pending_node_id=? WHERE id=? AND node_id=?",
                        target.id(), shard, old);
                    try {
                        try {
                            http.send(route(target.endpoint()), "PUT", "/api/v1/shards/" + shard, shards[i],
                                "application/octet-stream");
                        } catch (HttpTransport.HttpFailure e) {
                            if (e.status != 409)
                                throw e;
                        }
                        transferred += shards[i].length;
                        throttle(started, transferred);
                        byte[] check = http.send(route(target.endpoint()), "GET", "/api/v1/shards/" + shard,
                            null, "application/octet-stream");
                        transferred += check.length;
                        throttle(started, transferred);
                        if (check.length != c.shardBytes()
                            || !ShardCodec.hash(check).equals(c.shards().get(i).hash()))
                            throw new IllegalStateException("Repair target verification failed");
                        int updated = manifests.database().update(
                            "UPDATE shard_assignments SET node_id=?,pending_node_id=NULL,damaged=FALSE WHERE "
                            + "id=? AND node_id=? AND pending_node_id=?",
                            target.id(), shard, old, target.id());
                        if (updated == 1) {
                            occupied.add(target.id());
                            repaired++;
                            break;
                        }
                    } catch (Exception e) {
                        LOG.warn("repair shard={} target={} failed: {}", shard, target.id(), e.toString());
                    }
                }
            }
        return new int[] {repaired, missing - repaired};
    }
    private void throttle(long start, long bytes) {
        long wait = (long) (bytes * 1e9 / bytesPerSecond) - (System.nanoTime() - start);
        if (wait > 0)
            LockSupport.parkNanos(wait);
    }
    @Scheduled(fixedDelayString = "${store.repair-poll-ms:5000}")
    public synchronized void automatic() {
        try {
            var db = manifests.database();
            var nodes = db.queryForList("SELECT id FROM node_registrations", UUID.class);
            for (UUID node : nodes)
                if (detector.repairable(node))
                    db.update("UPDATE shard_assignments SET damaged=TRUE WHERE node_id=?", node);
            // Most damaged first: fewer surviving shards have higher urgency. Limit each poll's work.
            var queue = db.queryForList(
                "SELECT a.object_id,a.chunk_index FROM shard_assignments a JOIN objects o ON "
                + "a.object_id=o.id JOIN chunks c ON c.object_id=a.object_id AND c.chunk_index=a.chunk_index "
                + "WHERE o.committed=TRUE AND c.retry_after<=CURRENT_TIMESTAMP GROUP BY "
                + "a.object_id,a.chunk_index HAVING SUM(CASE WHEN a.damaged THEN 1 ELSE 0 END)>0 ORDER BY "
                + "SUM(CASE WHEN a.damaged THEN 1 ELSE 0 END) DESC,a.object_id,a.chunk_index LIMIT 32");
            for (var row : queue) {
                UUID id = (UUID) row.get("object_id");
                int index = ((Number) row.get("chunk_index")).intValue();
                long start = System.nanoTime();
                int[] result = repair(manifests.chunk(id, index));
                if (result[1] > 0)
                    db.update("UPDATE chunks SET retry_after=? WHERE object_id=? AND chunk_index=?",
                        java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30)), id, index);
                LOG.info("automatic_repair object={} chunk={} repaired={} pending={} elapsed_seconds={}", id,
                    index, result[0], result[1], (System.nanoTime() - start) / 1e9);
            }
        } catch (Exception e) {
            LOG.warn("Repair poll failed; will retry: {}", e.toString());
        }
    }
}
