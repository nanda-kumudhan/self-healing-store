package dev.healstore.coordinator.registry;

import dev.healstore.common.api.NodeView;
import dev.healstore.common.api.RegisterNodeRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NodeRegistry {
    private final NodeRegistrationRepository repository;
    private final NodeRegistrationWriter writer;
    public NodeRegistry(NodeRegistrationRepository repository, NodeRegistrationWriter writer) {
        this.repository = repository; this.writer = writer;
    }

    @Transactional(readOnly = true)
    public List<NodeView> list() {
        return repository.findAll().stream().map(NodeRegistration::view)
            .sorted(java.util.Comparator.comparing(n -> n.id().toString())).toList();
    }

    @Transactional(readOnly = true)
    public NodeView get(UUID id) {
        return repository.findById(id).map(NodeRegistration::view)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Node is not registered."));
    }

    @Transactional
    public NodeView register(RegisterNodeRequest request) {
        var uri = request.endpoint();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || uri.toASCIIString().length() > 2048) {
            throw new IllegalArgumentException("A node endpoint must be an HTTPS URI without credentials, query or fragment.");
        }
        if (repository.existsById(request.id()) || repository.existsByEndpoint(uri.toASCIIString())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Node ID or endpoint is already registered.");
        }
        try {
            // Insert a new row so two registrations with the same ID cannot silently overwrite each other.
            var registration = new NodeRegistration(request.id(), uri,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            writer.insert(registration);
            return registration.view();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Node ID or endpoint is already registered.");
        }
    }

}
