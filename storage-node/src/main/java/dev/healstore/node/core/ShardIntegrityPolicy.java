package dev.healstore.node.core;

import dev.healstore.common.model.Manifest.Grant;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ShardIntegrityPolicy {
    public void verify(Path shard, Grant expected) {
        try {
            if (Files.size(shard) != expected.bytes())
                throw new SecurityException("Shard length mismatch");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(shard)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected.hash()))
                throw new SecurityException("Shard hash mismatch; treat as missing");
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot verify shard", e);
        }
    }
}
