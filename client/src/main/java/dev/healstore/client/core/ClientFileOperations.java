package dev.healstore.client.core;

import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest;
import dev.healstore.common.model.Manifest.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** One chunk and five coded shards are held at a time, independent of file size. */
public class ClientFileOperations {
    private final HttpTransport http;
    private final URI coordinator;
    private final UnaryOperator<URI> route;
    public ClientFileOperations() {
        this(null, null, UnaryOperator.identity());
    }
    public ClientFileOperations(HttpTransport http, URI coordinator, UnaryOperator<URI> route) {
        this.http = http;
        this.coordinator = coordinator;
        this.route = route;
    }
    public UUID upload(Path source, char[] passphrase) throws Exception {
        if (!Files.isRegularFile(source))
            throw new IllegalArgumentException("Source must be a regular file");
        UUID id = UUID.randomUUID();
        byte[] key = FileCrypto.random(32);
        try (var crypto = new FileCrypto(id, key); var in = Files.newInputStream(source)) {
            Header initial = FileCrypto.wrap(id, key, passphrase);
            http.json(coordinator, "POST", "/api/v1/uploads", initial, Header.class);
            long total = 0;
            int index = 0;
            while (true) {
                byte[] plain = in.readNBytes(Manifest.CHUNK_BYTES);
                if (plain.length == 0)
                    break;
                try {
                    byte[][] shards = ShardCodec.encode(crypto.encrypt(index, plain));
                    Chunk chunk = metadata(id, index, plain.length, shards, crypto);
                    var stored =
                        http.json(coordinator, "PUT", chunkPath(id, index), chunk, StoredChunk.class);
                    for (int j = 0; j < 5; j++)
                        http.send(route.apply(stored.locations().get(j).endpoint()), "PUT",
                            "/api/v1/shards/" + chunk.shards().get(j).id(), shards[j],
                            "application/octet-stream");
                    http.json(coordinator, "POST", chunkPath(id, index) + "/commit", null, Void.class);
                    total += plain.length;
                    index = Math.incrementExact(index);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            }
            http.json(coordinator, "POST", "/api/v1/objects/" + id + "/commit",
                crypto.finish(initial, total, index), Header.class);
            return id;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
    public void download(UUID id, Path destination, char[] passphrase) throws Exception {
        checkDestination(destination);
        Header header = http.json(coordinator, "GET", "/api/v1/objects/" + id, null, Header.class);
        if (!id.equals(header.objectId()))
            throw new SecurityException("Wrong object in manifest");
        try (var crypto = FileCrypto.unwrap(header, passphrase)) {
            Path temporary = temporary(destination);
            try {
                long written = 0;
                try (var out = Files.newOutputStream(temporary)) {
                    for (int i = 0; i < header.chunks(); i++) {
                        var stored = http.json(coordinator, "GET", chunkPath(id, i), null, StoredChunk.class);
                        var chunk = stored.chunk();
                        chunk.validate();
                        if (!chunk.objectId().equals(id) || chunk.index() != i
                            || chunk.plainBytes() != Math.min(Manifest.CHUNK_BYTES, header.bytes() - written))
                            throw new SecurityException("Unexpected chunk order or length");
                        crypto.verify(chunk.canonical(), chunk.authentication());
                        byte[][] shards = new byte[5][];
                        boolean[] present = new boolean[5];
                        for (int j = 0; j < 5; j++) {
                            try {
                                shards[j] = http.send(route.apply(stored.locations().get(j).endpoint()),
                                    "GET", "/api/v1/shards/" + chunk.shards().get(j).id(), null,
                                    "application/octet-stream");
                                present[j] = shards[j].length == chunk.shardBytes()
                                    && ShardCodec.hash(shards[j]).equals(chunk.shards().get(j).hash());
                            } catch (java.io.IOException e) {
                                present[j] = false;
                            }
                        }
                        ShardCodec.recover(shards, present, chunk.shardBytes());
                        byte[] plain = crypto.decrypt(
                            i, chunk.plainBytes(), ShardCodec.join(shards, chunk.plainBytes() + 16));
                        try {
                            out.write(plain);
                            written += plain.length;
                        } finally {
                            Arrays.fill(plain, (byte) 0);
                        }
                    }
                }
                if (written != header.bytes())
                    throw new SecurityException("File length mismatch");
                publish(temporary, destination);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
    public void localRoundTrip(Path source, Path destination) throws Exception {
        checkDestination(destination);
        byte[] key = FileCrypto.random(32);
        UUID id = UUID.randomUUID();
        Path work = Files.createTempDirectory("healstore-local-");
        Path temp = temporary(destination);
        try (var crypto = new FileCrypto(id, key); var in = Files.newInputStream(source);
            var out = Files.newOutputStream(temp)) {
            int index = 0;
            while (true) {
                byte[] plain = in.readNBytes(Manifest.CHUNK_BYTES);
                if (plain.length == 0)
                    break;
                try {
                    var shards = ShardCodec.encode(crypto.encrypt(index, plain));
                    var chunk = metadata(id, index, plain.length, shards, crypto);
                    for (int j = 0; j < 5; j++) Files.write(work.resolve(j + ".shard"), shards[j]);
                    // Exercise disk reconstruction with two missing shards.
                    boolean[] present = {false, true, false, true, true};
                    for (int j = 0; j < 5; j++)
                        shards[j] = present[j] ? Files.readAllBytes(work.resolve(j + ".shard")) : null;
                    ShardCodec.recover(shards, present, chunk.shardBytes());
                    byte[] decoded =
                        crypto.decrypt(index, plain.length, ShardCodec.join(shards, plain.length + 16));
                    try {
                        out.write(decoded);
                    } finally {
                        Arrays.fill(decoded, (byte) 0);
                    }
                    index = Math.incrementExact(index);
                } finally {
                    Arrays.fill(plain, (byte) 0);
                }
            }
            out.close();
            publish(temp, destination);
        } finally {
            Arrays.fill(key, (byte) 0);
            Files.deleteIfExists(temp);
            for (int j = 0; j < 5; j++) Files.deleteIfExists(work.resolve(j + ".shard"));
            Files.deleteIfExists(work);
        }
    }
    public static Chunk metadata(UUID id, int index, int size, byte[][] shards, FileCrypto crypto)
        throws Exception {
        var list = new ArrayList<Shard>();
        for (var bytes : shards) list.add(new Shard(UUID.randomUUID(), ShardCodec.hash(bytes)));
        var unsigned = new Chunk(id, index, size, List.copyOf(list), "");
        return new Chunk(id, index, size, unsigned.shards(), crypto.authenticate(unsigned.canonical()));
    }
    private static String chunkPath(UUID id, int index) {
        return "/api/v1/objects/" + id + "/chunks/" + index;
    }
    private static void checkDestination(Path p) throws Exception {
        if (Files.exists(p))
            throw new FileAlreadyExistsException(p.toString());
    }
    private static Path temporary(Path p) throws Exception {
        return Files.createTempFile(p.toAbsolutePath().getParent(), ".download-", ".part");
    }
    private static void publish(Path temp, Path dest) throws Exception {
        try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.createLink(dest, temp);
    }
}
