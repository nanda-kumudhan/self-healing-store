package dev.healstore.common.api;

import dev.healstore.common.core.HandsOnTask;
import java.util.List;

public record Capabilities(String service, String stage, boolean fileOperationsReady,
                           List<HandsOnTask> pending) {
    public static Capabilities scaffold(String service) {
        return new Capabilities(service, "scaffold", false, List.of(HandsOnTask.values()));
    }
}
