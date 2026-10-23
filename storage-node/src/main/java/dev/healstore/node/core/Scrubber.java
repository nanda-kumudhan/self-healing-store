package dev.healstore.node.core;

import dev.healstore.common.core.HandsOnRequiredException;
import dev.healstore.common.core.HandsOnTask;
import org.springframework.stereotype.Component;

@Component
public class Scrubber {
    public void request() {
        // HANDS_ON: scan stored shards, check them and report damage on a schedule.
        throw new HandsOnRequiredException(HandsOnTask.SCRUBBING);
    }
}
