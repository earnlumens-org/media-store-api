package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.ModerationJobService;
import org.earnlumens.mediastore.domain.media.dto.request.ModerationCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.ModerationHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.ModerationDecision;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModerationCallbackController}.
 */
class ModerationCallbackControllerTest {

    private static final String SECRET = "test-mod-secret";
    private static final String TENANT = "earnlumens";

    private ModerationJobService jobService;
    private ModerationCallbackController controller;

    @BeforeEach
    void setUp() {
        jobService = mock(ModerationJobService.class);
        controller = new ModerationCallbackController(jobService, SECRET);
    }

    private ModerationJob completedJob(ModerationDecision decision) {
        ModerationJob job = new ModerationJob();
        job.setId("mod-1");
        job.setStatus(ModerationJobStatus.COMPLETED);
        job.setDecision(decision);
        return job;
    }

    private ModerationJob retriedJob() {
        ModerationJob job = new ModerationJob();
        job.setId("mod-1");
        job.setStatus(ModerationJobStatus.PENDING);
        return job;
    }

    // ─── Auth ───────────────────────────────────────────────────

    @Nested
    class Auth {

        @Test
        void missingSecret_returns403() {
            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "COMPLETED", "APPROVE", 0.95, List.of(), "ok", "GEMINI", null, null);

            ResponseEntity<?> resp = controller.handleCallback(null, req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void wrongSecret_returns403() {
            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "COMPLETED", "APPROVE", 0.95, List.of(), "ok", "GEMINI", null, null);

            ResponseEntity<?> resp = controller.handleCallback("wrong-secret", req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void correctSecret_proceeds() {
            when(jobService.completeJob(eq(TENANT), eq("mod-1"), eq("APPROVE"), eq(0.95),
                    anyList(), eq("ok"), eq("GEMINI"), any()))
                    .thenReturn(Optional.of(completedJob(ModerationDecision.APPROVE)));

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "COMPLETED", "APPROVE", 0.95, List.of(), "ok", "GEMINI", null, null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
        }
    }

    // ─── COMPLETED ──────────────────────────────────────────────

    @Nested
    class Completed {

        @Test
        void approve_returns200WithDecision() {
            when(jobService.completeJob(eq(TENANT), eq("mod-1"), eq("APPROVE"), eq(0.92),
                    anyList(), eq("Content approved"), eq("GEMINI"), any()))
                    .thenReturn(Optional.of(completedJob(ModerationDecision.APPROVE)));

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "COMPLETED", "APPROVE", 0.92,
                    List.of("NONE"), "Content approved", "GEMINI", null, null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertNotNull(body);
            assertEquals("APPROVE", body.get("decision"));
        }

        @Test
        void reject_returns200WithDecision() {
            when(jobService.completeJob(eq(TENANT), eq("mod-1"), eq("REJECT"), eq(0.99),
                    anyList(), eq("NSFW"), eq("NUDENET"), any()))
                    .thenReturn(Optional.of(completedJob(ModerationDecision.REJECT)));

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "COMPLETED", "REJECT", 0.99,
                    List.of("NSFW"), "NSFW", "NUDENET", null, null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertNotNull(body);
            assertEquals("REJECT", body.get("decision"));
        }

        @Test
        void jobNotFound_returns404() {
            when(jobService.completeJob(eq(TENANT), eq("no-id"), anyString(), any(),
                    anyList(), anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "no-id", TENANT, "COMPLETED", "APPROVE", 0.9,
                    List.of(), "ok", "GEMINI", null, null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(404, resp.getStatusCode().value());
        }
    }

    // ─── FAILED ─────────────────────────────────────────────────

    @Nested
    class Failed {

        @Test
        void failWithRetry_returns200WithPendingStatus() {
            when(jobService.failJob(TENANT, "mod-1", "NudeNet OOM"))
                    .thenReturn(Optional.of(retriedJob()));

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "mod-1", TENANT, "FAILED", null, null,
                    null, null, null, "NudeNet OOM", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertNotNull(body);
            assertEquals("PENDING", body.get("status"));
        }

        @Test
        void failJobNotFound_returns404() {
            when(jobService.failJob(TENANT, "no-id", "crash"))
                    .thenReturn(Optional.empty());

            ModerationCallbackRequest req = new ModerationCallbackRequest(
                    "no-id", TENANT, "FAILED", null, null,
                    null, null, null, "crash", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(404, resp.getStatusCode().value());
        }
    }

    // ─── Invalid status ─────────────────────────────────────────

    @Test
    void invalidStatus_returns400() {
        ModerationCallbackRequest req = new ModerationCallbackRequest(
                "mod-1", TENANT, "BANANA", null, null,
                null, null, null, null, null);

        ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

        assertEquals(400, resp.getStatusCode().value());
    }

    // ─── Heartbeat ──────────────────────────────────────────────

    @Nested
    class HeartbeatEndpoint {

        @Test
        void validHeartbeat_returns200() {
            ModerationHeartbeatRequest req = new ModerationHeartbeatRequest("mod-1", TENANT);

            ResponseEntity<?> resp = controller.heartbeat(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            verify(jobService).heartbeat("mod-1", TENANT);
        }

        @Test
        void missingSecret_returns403() {
            ModerationHeartbeatRequest req = new ModerationHeartbeatRequest("mod-1", TENANT);

            ResponseEntity<?> resp = controller.heartbeat(null, req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }
    }

    // ─── Status endpoint ────────────────────────────────────────

    @Nested
    class StatusEndpoint {

        @Test
        void validSecret_returnsCounts() {
            when(jobService.findByStatus(any())).thenReturn(List.of());

            ResponseEntity<?> resp = controller.jobStatus(SECRET);

            assertEquals(200, resp.getStatusCode().value());
        }

        @Test
        void wrongSecret_returns403() {
            ResponseEntity<?> resp = controller.jobStatus("wrong");

            assertEquals(403, resp.getStatusCode().value());
        }
    }
}
