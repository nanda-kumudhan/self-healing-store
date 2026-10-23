package dev.healstore.coordinator.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RepairService {
    public void request(UUID objectId) {
        // HANDS_ON: queue repairs, rebuild onto spare nodes, update metadata and cap bandwidth.
        throw new HandsOnRequiredException(HandsOnTask.REPAIR);
    }
}
