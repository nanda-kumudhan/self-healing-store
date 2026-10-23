package dev.healstore.coordinator;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.healstore.common.api.RegisterNodeRequest;
import dev.healstore.coordinator.registry.NodeRegistry;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CoordinatorApiTest {
    @Autowired MockMvc mvc;
    @Autowired NodeRegistry registry;

    @Test
    void capabilitiesReportImplementation() throws Exception {
        mvc.perform(get("/api/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileOperationsReady").value(true));
    }

    @Test
    void protectedRegistryAndRepairFailClosed() throws Exception {
        mvc.perform(get("/api/v1/nodes")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/objects/00000000-0000-0000-0000-000000000001/repairs"))
            .andExpect(status().isForbidden());
    }

    @Test
    void repositoryPersistsRegistrationsWithoutClaimingLiveness() {
        UUID id = UUID.randomUUID();
        var endpoint = URI.create("https://node-" + id + ":8443");
        var saved = registry.register(new RegisterNodeRequest(id, endpoint));
        assertEquals(id, saved.id());
        assertEquals(endpoint, saved.endpoint());
        assertEquals(saved, registry.get(id));
        assertTrue(registry.list().stream().anyMatch(n -> n.id().equals(id)));
        assertThrows(
            ResponseStatusException.class, () -> registry.register(new RegisterNodeRequest(id, endpoint)));
    }

    @Test
    void registryReturnsNotFoundForUnknownNode() {
        var error = assertThrows(ResponseStatusException.class, () -> registry.get(UUID.randomUUID()));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void registryRejectsPlaintextAndCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException.class,
            ()
                -> registry.register(
                    new RegisterNodeRequest(UUID.randomUUID(), URI.create("http://node:8080"))));
        assertThrows(IllegalArgumentException.class,
            ()
                -> registry.register(new RegisterNodeRequest(
                    UUID.randomUUID(), URI.create("https://user:password@node:8443"))));
    }
}
