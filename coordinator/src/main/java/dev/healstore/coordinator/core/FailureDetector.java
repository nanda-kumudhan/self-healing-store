package dev.healstore.coordinator.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FailureDetector {
    public void heartbeat(UUID nodeId) {
        // HANDS_ON: track heartbeats and decide when a node has been missing long enough.
        throw new HandsOnRequiredException(HandsOnTask.FAILURE_DETECTION);
    }
}
