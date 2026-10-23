package dev.healstore.coordinator.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import org.springframework.stereotype.Component;

@Component
public class PlacementPolicy {
    public void planUpload() {
        // HANDS_ON: decide what this needs as input and which nodes should hold each shard.
        throw new HandsOnRequiredException(HandsOnTask.PLACEMENT);
    }
}
