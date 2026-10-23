package dev.healstore.common.api;

import java.util.List;

public record Capabilities(String service, String stage, boolean fileOperationsReady, List<String> pending) {
    public static Capabilities implemented(String service) {
        return new Capabilities(service, "implemented", true, List.of());
    }
}
