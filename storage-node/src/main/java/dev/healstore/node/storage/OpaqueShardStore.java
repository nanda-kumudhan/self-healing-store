package dev.healstore.node.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Streams shard bytes to disk. Coding, hashes, repair and manifests aren't handled here. */
@Component
public class OpaqueShardStore {
    private final Path directory;
    private final long maxBytes;

    public OpaqueShardStore(@Value("${store.data-directory}") Path directory,
                           @Value("${store.max-shard-bytes}") long maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("max-shard-bytes must be positive");
        this.directory = directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        Files.createDirectories(this.directory);
    }

    public void put(UUID id, InputStream source, long declaredLength, Consumer<Path> validate) throws IOException {
        if (declaredLength > maxBytes) throw new ShardTooLargeException();
        Path temporary = Files.createTempFile(directory, ".incoming-", ".part");
        try {
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 var output = java.nio.channels.Channels.newOutputStream(channel)) {
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count > maxBytes - written) throw new ShardTooLargeException();
                    output.write(buffer, 0, count);
                    written += count;
                }
                if (declaredLength >= 0 && written != declaredLength) {
                    throw new IllegalArgumentException("Body length differs from Content-Length.");
                }
                output.flush();
                channel.force(true);
            }
            validate.accept(temporary);
            // A hard link publishes the finished file without replacing an existing shard.
            Files.createLink(path(id), temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path get(UUID id) throws FileNotFoundException {
        var path = path(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException();
        return path;
    }

    private Path path(UUID id) { return directory.resolve(id.toString() + ".shard"); }
}
