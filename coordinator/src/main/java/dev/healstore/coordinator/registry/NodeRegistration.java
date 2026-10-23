package dev.healstore.coordinator.registry;

import dev.healstore.common.api.NodeView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "node_registrations")
public class NodeRegistration {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 2048) private String endpoint;
    @Column(name = "registered_at", nullable = false, updatable = false) private Instant registeredAt;

    protected NodeRegistration() { }

    NodeRegistration(UUID id, URI endpoint, Instant registeredAt) {
        this.id = id;
        this.endpoint = endpoint.toASCIIString();
        this.registeredAt = registeredAt;
    }

    public NodeView view() { return new NodeView(id, URI.create(endpoint), registeredAt); }
}
