package dev.healstore.common.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Version 1 is fixed at 3+2, 1 MiB chunks, AES-256-GCM and Argon2id. */
public final class Manifest {
    private Manifest() {}
    public static final int CHUNK_BYTES = 1024 * 1024;
    public record Header(int version, UUID objectId, String salt, String wrapNonce, String wrappedKey,
        long bytes, int chunks, String authentication) {
        public void validate() {
            if (version != 1 || objectId == null || bytes < 0
                || bytes > (long) Integer.MAX_VALUE * CHUNK_BYTES || chunks < 0
                || chunks != (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid manifest header");
            decoded(salt, 16);
            decoded(wrapNonce, 12);
            decoded(wrappedKey, 48);
            if (!authentication.isEmpty())
                decoded(authentication, 32);
        }
        public byte[] canonical() {
            return utf8("healstore-header-v1|" + objectId + "|" + salt + "|" + wrapNonce + "|" + wrappedKey
                + "|" + bytes + "|" + chunks);
        }
    }
    public record Shard(UUID id, String hash) {}
    public record Chunk(UUID objectId, int index, int plainBytes, List<Shard> shards, String authentication) {
        public void validate() {
            if (objectId == null || index < 0 || plainBytes < 1 || plainBytes > CHUNK_BYTES || shards == null
                || shards.size() != 5)
                throw new IllegalArgumentException("Invalid chunk");
            var ids = new HashSet<UUID>();
            for (var shard : shards) {
                if (shard.id() == null || !ids.add(shard.id()) || shard.hash() == null
                    || !shard.hash().matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid shard metadata");
            }
            decoded(authentication, 32);
        }
        public int shardBytes() {
            return (plainBytes + 16 + 2) / 3;
        }
        public byte[] canonical() {
            var s = new StringBuilder("healstore-chunk-v1|")
                        .append(objectId)
                        .append('|')
                        .append(index)
                        .append('|')
                        .append(plainBytes);
            for (var shard : shards) s.append('|').append(shard.id()).append('|').append(shard.hash());
            return utf8(s.toString());
        }
    }
    public record Location(UUID nodeId, URI endpoint) {}
    public record StoredChunk(Chunk chunk, List<Location> locations) {}
    public record Grant(String hash, int bytes) {}
    public record RepairResult(int repairedShards, int pendingShards, double seconds) {}
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
    public static byte[] decoded(String s, int bytes) {
        if (s == null || s.length() > bytes * 2 + 8)
            throw new IllegalArgumentException("Invalid encoded field");
        byte[] b = Base64.getDecoder().decode(s);
        if (b.length != bytes)
            throw new IllegalArgumentException("Wrong encoded field length");
        return b;
    }
}
