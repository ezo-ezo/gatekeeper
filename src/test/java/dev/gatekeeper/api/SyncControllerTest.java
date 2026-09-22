package dev.gatekeeper.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.gatekeeper.reconcile.Reconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the HTTP layer with {@code standaloneSetup} rather than a full
 * Spring context: each test gets its own freshly constructed {@link
 * Reconciler}, so nothing leaks between test methods the way a shared
 * Spring-managed singleton bean would if the context were cached and reused.
 */
class SyncControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Reconciler reconciler = new Reconciler();
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SyncController(reconciler), new ReconciliationController(reconciler))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static String scanJson(String ticketId, String scannedAtIso, boolean accepted) {
        return """
                {"scans":[{"ticketId":"%s","scannedAt":"%s","accepted":%s}]}
                """.formatted(ticketId, scannedAtIso, accepted);
    }

    @Test
    void syncingAScanIngestsItAndReturnsCounts() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanJson("t1", "2026-09-26T18:00:00Z", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateId").value("gate-1"))
                .andExpect(jsonPath("$.scansReceived").value(1))
                .andExpect(jsonPath("$.totalScansConsidered").value(1));
    }

    @Test
    void gateIdComesFromThePathNotTheBody() throws Exception {
        // The request DTO has no gateId field at all, so a gate can only ever
        // report scans under the identity in the URL it authenticated as.
        mockMvc.perform(post("/gates/gate-9/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanJson("t1", "2026-09-26T18:00:00Z", true)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/reconciliation"))
                .andExpect(jsonPath("$.decisions[0].accepted.gateId").value("gate-9"));
    }

    @Test
    void resyncingTheSameScanDoesNotDoubleCount() throws Exception {
        String body = scanJson("t1", "2026-09-26T18:00:00Z", true);

        mockMvc.perform(post("/gates/gate-1/sync").contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(post("/gates/gate-1/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.totalScansConsidered").value(1));
    }

    @Test
    void emptyScanListIsAcceptedAndChangesNothing() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scans":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scansReceived").value(0))
                .andExpect(jsonPath("$.totalScansConsidered").value(0));
    }

    @Test
    void twoGatesAcceptingTheSameTicketShowsUpAsAConflict() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scanJson("t1", "2026-09-26T18:00:00Z", true)));
        mockMvc.perform(post("/gates/gate-2/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scanJson("t1", "2026-09-26T18:00:05Z", true)));

        mockMvc.perform(get("/reconciliation/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ticketId").value("t1"))
                .andExpect(jsonPath("$[0].accepted.gateId").value("gate-1")) // earliest wins
                .andExpect(jsonPath("$[0].conflicts", hasSize(1)))
                .andExpect(jsonPath("$[0].conflicts[0].gateId").value("gate-2"));

        mockMvc.perform(get("/reconciliation"))
                .andExpect(jsonPath("$.conflictCount").value(1));
    }

    @Test
    void rejectsABlankTicketId() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scanJson("", "2026-09-26T18:00:00Z", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void rejectsAMissingAcceptedField() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scans":[{"ticketId":"t1","scannedAt":"2026-09-26T18:00:00Z"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingScannedAt() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scans":[{"ticketId":"t1","accepted":true}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reconciliationReportStartsEmpty() throws Exception {
        mockMvc.perform(get("/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions", hasSize(0)))
                .andExpect(jsonPath("$.totalScansConsidered").value(0))
                .andExpect(jsonPath("$.conflictCount").value(0));
    }

    @Test
    void rejectedScansDoNotShowUpInTheReport() throws Exception {
        mockMvc.perform(post("/gates/gate-1/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scanJson("t1", "2026-09-26T18:00:00Z", false)));

        mockMvc.perform(get("/reconciliation"))
                .andExpect(jsonPath("$.decisions", hasSize(0)))
                .andExpect(jsonPath("$.totalScansConsidered").value(1));
    }
}
