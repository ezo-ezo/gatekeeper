package dev.gatekeeper.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application, for real: full Spring context, the JPA-backed store
 * on a real (in-memory) H2 database, and the actual Prometheus endpoint. This
 * is the test that proves the metered wrapper is what the controllers
 * actually receive, which the unit tests of the pieces cannot.
 *
 * <p>Metrics are cumulative and the database is shared within a context, so a
 * context reused across test methods would let one test's traffic change
 * another's counters, and the result would depend on execution order. Each
 * test therefore gets its own fresh context; the cost is one extra startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ObservabilityIntegrationTest {

    private static final String SYNC_BODY = """
            {"scans":[{"ticketId":"t-1","scannedAt":"2026-09-26T18:00:00Z","accepted":true}]}
            """;

    @Autowired
    private MockMvc mvc;

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void sync(String gateId) throws Exception {
        mvc.perform(post("/gates/" + gateId + "/sync").contentType(MediaType.APPLICATION_JSON).content(SYNC_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void aResentScanIsCountedAsReceivedTwiceButStoredOnce() throws Exception {
        sync("gate-1");
        sync("gate-1"); // an exact retry, as after a dropped connection
        mvc.perform(get("/reconciliation")).andExpect(status().isOk());

        String metrics = scrape();

        assertThat(metrics).containsPattern("gatekeeper_scans_received_total\\{[^}]*outcome=\"accepted\"[^}]*\\}\\s+2(\\.0)?\\b");
        assertThat(metrics).containsPattern("gatekeeper_scans_stored_total(\\{[^}]*\\})?\\s+1(\\.0)?\\b");
        assertThat(metrics).containsPattern("gatekeeper_scans_held(\\{[^}]*\\})?\\s+1(\\.0)?\\b");
        assertThat(metrics).contains("gatekeeper_reconcile_seconds_count");
        assertThat(metrics).contains("gatekeeper_store_seconds_count");
    }

    @Test
    void requestLatencyIsRecordedPerRouteNotPerGate() throws Exception {
        sync("gate-77");

        String metrics = scrape();

        // The route template, so the number of series is bounded by the number of routes...
        assertThat(metrics).contains("uri=\"/gates/{gateId}/sync\"");
        // ...and nothing a client chose ever appears as a label value.
        assertThat(metrics).doesNotContain("gate-77");
        assertThat(metrics).doesNotContain("t-1\"");
    }
}
