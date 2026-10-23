package dev.healstore.coordinator.core;

import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.*;
import dev.healstore.coordinator.registry.NodeRegistry;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ManifestService {
    private final JdbcTemplate db;
    private final PlacementPolicy placement;
    private final NodeRegistry registry;
    public ManifestService(JdbcTemplate db, PlacementPolicy placement, NodeRegistry registry) {
        this.db = db;
        this.placement = placement;
        this.registry = registry;
    }
    public void owner(UUID id, String caller) {
        var owners = db.queryForList("SELECT owner FROM objects WHERE id=?", String.class, id);
        if (owners.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object does not exist");
        if (!owners.getFirst().equals(caller))
            throw new SecurityException("Object belongs to another certificate");
    }
    @Transactional
    public Header create(Header header, String owner) {
        header.validate();
        if (header.bytes() != 0 || header.chunks() != 0 || !header.authentication().isEmpty())
            throw new IllegalArgumentException("Expected initial header");
        db.update(
            "INSERT INTO objects(id,owner,header,committed,created_at) VALUES(?,?,?,FALSE,CURRENT_TIMESTAMP)",
            header.objectId(), owner, Json.write(header));
        return header;
    }
    public Header read(UUID id) {
        var rows =
            db.queryForList("SELECT header FROM objects WHERE id=? AND committed=TRUE", String.class, id);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No committed object");
        return Json.read(rows.getFirst(), Header.class);
    }
    @Transactional
    public StoredChunk plan(UUID id, int index, Chunk chunk) {
        lock(id);
        chunk.validate();
        if (!id.equals(chunk.objectId()) || index != chunk.index())
            throw new IllegalArgumentException("Wrong chunk identity");
        var existing = db.queryForList(
            "SELECT metadata FROM chunks WHERE object_id=? AND chunk_index=?", String.class, id, index);
        if (!existing.isEmpty()) {
            if (!Json.read(existing.getFirst(), Chunk.class).equals(chunk))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chunk is immutable");
            return chunk(id, index);
        }
        var count = db.queryForObject("SELECT count(*) FROM chunks WHERE object_id=?", Integer.class, id);
        if (index != count)
            throw new IllegalArgumentException("Chunks must be appended in order");
        if (index > 0
            && chunk(id, index - 1).chunk().plainBytes() != dev.healstore.common.model.Manifest.CHUNK_BYTES)
            throw new IllegalArgumentException("Only final chunk may be short");
        var nodes = placement.planUpload(id + ":" + index);
        db.update(
            "INSERT INTO chunks(object_id,chunk_index,metadata) VALUES(?,?,?)", id, index, Json.write(chunk));
        for (int i = 0; i < 5; i++)
            db.update("INSERT INTO "
                      + "shard_assignments(id,object_id,chunk_index,shard_index,node_id,expected_hash,shard_"
                      + "bytes) VALUES(?,?,?,?,?,?,?)",
                chunk.shards().get(i).id(), id, index, i, nodes.get(i).id(), chunk.shards().get(i).hash(),
                chunk.shardBytes());
        return chunk(id, index);
    }
    private void lock(UUID id) {
        var rows = db.queryForList("SELECT committed FROM objects WHERE id=? FOR UPDATE", Boolean.class, id);
        if (rows.isEmpty())
            throw new IllegalArgumentException("Unknown upload");
        if (rows.getFirst())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Object is already committed");
    }
    public StoredChunk chunk(UUID id, int index) {
        var rows = db.queryForList(
            "SELECT metadata FROM chunks WHERE object_id=? AND chunk_index=?", String.class, id, index);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk does not exist");
        List<Location> locations = db.query(
            "SELECT node_id FROM shard_assignments WHERE object_id=? AND chunk_index=? ORDER BY shard_index",
            (rs, n)
                -> {
                var node = registry.get(rs.getObject(1, UUID.class));
                return new Location(node.id(), node.endpoint());
            },
            id, index);
        return new StoredChunk(Json.read(rows.getFirst(), Chunk.class), locations);
    }
    @Transactional
    public Header commit(Header header) {
        header.validate();
        dev.healstore.common.model.Manifest.decoded(header.authentication(), 32);
        lock(header.objectId());
        Header initial = Json.read(
            db.queryForObject("SELECT header FROM objects WHERE id=?", String.class, header.objectId()),
            Header.class);
        if (!initial.salt().equals(header.salt()) || !initial.wrapNonce().equals(header.wrapNonce())
            || !initial.wrappedKey().equals(header.wrappedKey()))
            throw new IllegalArgumentException("Key envelope changed");
        int count = db.queryForObject(
            "SELECT count(*) FROM chunks WHERE object_id=?", Integer.class, header.objectId());
        if (db.queryForObject("SELECT count(*) FROM chunks WHERE object_id=? AND verified=FALSE",
                Integer.class, header.objectId())
            != 0)
            throw new IllegalStateException("Upload has unverified chunks");
        if (count != header.chunks())
            throw new IllegalArgumentException("Wrong chunk count");
        long bytes = count == 0 ? 0
                                : (long) (count - 1) * dev.healstore.common.model.Manifest.CHUNK_BYTES
                + chunk(header.objectId(), count - 1).chunk().plainBytes();
        if (bytes != header.bytes())
            throw new IllegalArgumentException("Wrong byte count");
        db.update(
            "UPDATE objects SET header=?,committed=TRUE WHERE id=?", Json.write(header), header.objectId());
        return header;
    }
    public Grant grant(UUID shard, UUID node, String caller) {
        var rows = db.queryForList(
            "SELECT a.expected_hash,a.shard_bytes,o.owner FROM shard_assignments a JOIN objects o ON "
            + "a.object_id=o.id WHERE a.id=? AND (a.node_id=? OR a.pending_node_id=?)",
            shard, node, node);
        if (rows.isEmpty())
            throw new SecurityException("Shard is not assigned to this node");
        var row = rows.getFirst();
        if (!caller.equals("coordinator") && !caller.equals(row.get("owner")))
            throw new SecurityException("Shard belongs to another client");
        return new Grant((String) row.get("expected_hash"), ((Number) row.get("shard_bytes")).intValue());
    }
    public void damage(UUID shard, UUID node) {
        db.update("UPDATE shard_assignments SET damaged=TRUE WHERE id=? AND node_id=?", shard, node);
    }
    public List<UUID> committedObjects(int offset, int limit) {
        return db.queryForList(
            "SELECT id FROM objects WHERE committed=TRUE ORDER BY created_at,id LIMIT ? OFFSET ?", UUID.class,
            limit, offset);
    }
    public JdbcTemplate database() {
        return db;
    }
}
