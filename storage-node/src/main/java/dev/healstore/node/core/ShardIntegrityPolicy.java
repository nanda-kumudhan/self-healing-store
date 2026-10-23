package dev.healstore.node.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class ShardIntegrityPolicy {
    public void verify(Path opaqueShard) {
        // HANDS_ON: check against a trusted hash and treat a bad shard as missing.
        throw new HandsOnRequiredException(HandsOnTask.SHARD_INTEGRITY);
    }
}
