package dev.healstore.node;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "store.data-directory=${java.io.tmpdir}/healstore-api-test-${random.uuid}")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class StorageApiTest {
    @Autowired MockMvc mvc;

    @Test void capabilitiesNeverClaimTheStoreIsReady() throws Exception {
        mvc.perform(get("/api/v1/capabilities")).andExpect(status().isOk())
            .andExpect(jsonPath("$.fileOperationsReady").value(false));
    }

    @Test void cannotWriteUntilIdentityLogicIsImplemented() throws Exception {
        mvc.perform(put("/api/v1/shards/00000000-0000-0000-0000-000000000001")
            .contentType("application/octet-stream").content(new byte[]{1}))
            .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("HANDS_ON_REQUIRED"))
            .andExpect(jsonPath("$.task").value("PEER_IDENTITY"));
    }

    @Test void scrubDoesNotPretendToRun() throws Exception {
        mvc.perform(post("/api/v1/scrubs")).andExpect(status().isNotImplemented());
    }
}
