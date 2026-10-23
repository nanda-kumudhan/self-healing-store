package dev.healstore.node.core;

import dev.healstore.common.api.RegisterNodeRequest;
import dev.healstore.common.core.CertificateIdentity;
import dev.healstore.common.io.*;
import dev.healstore.common.model.Manifest.Grant;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NodeControl {
    private final URI coordinator, endpoint;
    private final UUID id;
    private final HttpTransport http;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NodeControl.class);
    public NodeControl(@Value("${store.coordinator:}") String coordinator,
        @Value("${store.node-name:node-1}") String name,
        @Value("${store.endpoint:https://node-1:8443}") URI endpoint) throws Exception {
        this.coordinator = coordinator.isBlank() ? null : URI.create(coordinator);
        this.endpoint = endpoint;
        id = new CertificateIdentity(name, "").nodeId();
        http = HttpTransport.service();
    }
    public Grant grant(UUID shard, String caller) throws Exception {
        if (coordinator == null)
            throw new IllegalStateException("Configure STORE_COORDINATOR before using shards");
        if (!caller.equals("coordinator") && !caller.matches("[0-9a-f]{64}"))
            throw new SecurityException("Invalid caller identity");
        return http.json(
            coordinator, "GET", "/api/v1/shards/" + shard + "/grant?caller=" + caller, null, Grant.class);
    }
    public void damage(UUID shard) throws Exception {
        if (coordinator != null)
            http.json(coordinator, "POST", "/api/v1/nodes/" + id + "/damage/" + shard, null, Void.class);
    }
    @Scheduled(fixedDelayString = "${store.heartbeat-ms:2000}", initialDelay = 1000)
    public void heartbeat() {
        if (coordinator == null)
            return;
        try {
            // Registration is idempotent and also refreshes the heartbeat after coordinator restarts.
            http.json(
                coordinator, "POST", "/api/v1/nodes", new RegisterNodeRequest(id, endpoint), Object.class);
        } catch (Exception e) {
            LOG.warn("Heartbeat failed; will retry: {}", e.toString());
        }
    }
}
