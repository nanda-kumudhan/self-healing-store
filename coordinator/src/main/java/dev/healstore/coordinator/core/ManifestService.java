package dev.healstore.coordinator.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ManifestService {
    public void read(UUID objectId) {
        // HANDS_ON: work out the manifest format and how its contents can be trusted.
        throw new HandsOnRequiredException(HandsOnTask.MANIFEST_FORMAT);
    }
}
