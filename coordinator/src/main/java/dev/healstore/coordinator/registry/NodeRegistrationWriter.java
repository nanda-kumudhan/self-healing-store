package dev.healstore.coordinator.registry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/** Keeping inserts in a repository lets Spring translate database errors. */
@Repository
public class NodeRegistrationWriter {
    @PersistenceContext private EntityManager entityManager;

    public void insert(NodeRegistration registration) {
        entityManager.persist(registration);
        entityManager.flush();
    }
}
