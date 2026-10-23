package dev.healstore.coordinator.registry;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRegistrationRepository extends JpaRepository<NodeRegistration, UUID> {
    boolean existsByEndpoint(String endpoint);
}
