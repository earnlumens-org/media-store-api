package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.TranscodingJobService;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.model.TranscodingJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TranscodingCallbackController}.
 */
class TranscodingCallbackControllerTest {

    private static final String SECRET = "test-secret-abc";

    private TranscodingJobService jobService;
    private TranscodingCallbackController controller;

    @BeforeEach
    void setUp() {
        jobService = mock(TranscodingJobService.class);
        controller = new TranscodingCallbackController(jobService, SECRET);
    }

    private TranscodingJob completedJob() {
        TranscodingJob job = new TranscodingJob();
        job.setId("job-1");
        job.setStatus(TranscodingJobStatus.COMPLETED);
        return job;
    }

    private TranscodingJob retriedJob() {
        TranscodingJob job = new TranscodingJob();
        job.setId("job-1");
        job.setStatus(TranscodingJobStatus.PENDING);
        return job;
    }

    private TranscodingJob deadJob() {
        TranscodingJob job = new TranscodingJob();
        job.setId("job-1");
        job.setStatus(TranscodingJobStatus.DEAD);
        return job;
    }

    // ─── Auth ───────────────────────────────────────────────────

    @Nested
    class Auth {

        @Test
        void missingSecret_returns403() {
            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", "prefix/", null);

            ResponseEntity<?> resp = controller.handleCallback(null, req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void wrongSecret_returns403() {
            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", "prefix/", null);

            ResponseEntity<?> resp = controller.handleCallback("wrong-secret", req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void correctSecret_proceeds() {
            when(jobService.completeJob("job-1", "prefix/"))
                    .thenReturn(Optional.of(completedJob()));

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", "prefix/", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
        }
    }

    // ─── COMPLETED ──────────────────────────────────────────────

    @Nested
    class Completed {

        @Test
        void validCompletion_returns200() {
            when(jobService.completeJob("job-1", "public/media/e1/hls/"))
                    .thenReturn(Optional.of(completedJob()));

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", "public/media/e1/hls/", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            verify(jobService).completeJob("job-1", "public/media/e1/hls/");
        }

        @Test
        void missingHlsPrefix_returns400() {
            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", null, null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(400, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void blankHlsPrefix_returns400() {
            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "COMPLETED", "  ", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(400, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void jobNotFound_returns404() {
            when(jobService.completeJob("no-job", "prefix/"))
                    .thenReturn(Optional.empty());

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "no-job", "COMPLETED", "prefix/", null);

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(404, resp.getStatusCode().value());
        }
    }

    // ─── FAILED ─────────────────────────────────────────────────

    @Nested
    class Failed {

        @Test
        void failedWithRetry_returns200WithPendingStatus() {
            when(jobService.failJob("job-1", "FFmpeg crash"))
                    .thenReturn(Optional.of(retriedJob()));

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "FAILED", null, "FFmpeg crash");

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) resp.getBody();
            assertEquals("PENDING", body.get("status"));
        }

        @Test
        void failedRetriesExhausted_returns200WithDeadStatus() {
            when(jobService.failJob("job-1", "Corrupt file"))
                    .thenReturn(Optional.of(deadJob()));

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "job-1", "FAILED", null, "Corrupt file");

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) resp.getBody();
            assertEquals("DEAD", body.get("status"));
        }

        @Test
        void jobNotFound_returns404() {
            when(jobService.failJob("no-job", "error"))
                    .thenReturn(Optional.empty());

            TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                    "no-job", "FAILED", null, "error");

            ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

            assertEquals(404, resp.getStatusCode().value());
        }
    }

    // ─── Invalid status ─────────────────────────────────────────

    @Test
    void invalidStatus_returns400() {
        TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                "job-1", "CANCELED", null, null);

        ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(jobService);
    }

    @Test
    void statusIsCaseInsensitive() {
        when(jobService.completeJob("job-1", "prefix/"))
                .thenReturn(Optional.of(completedJob()));

        TranscodingCallbackRequest req = new TranscodingCallbackRequest(
                "job-1", "completed", "prefix/", null);

        ResponseEntity<?> resp = controller.handleCallback(SECRET, req);

        assertEquals(200, resp.getStatusCode().value());
        verify(jobService).completeJob("job-1", "prefix/");
    }

    // ─── Heartbeat ──────────────────────────────────────────────

    @Nested
    class HeartbeatEndpoint {

        @Test
        void validSecret_returns200() {
            TranscodingHeartbeatRequest req = new TranscodingHeartbeatRequest("job-1");

            ResponseEntity<?> resp = controller.heartbeat(SECRET, req);

            assertEquals(200, resp.getStatusCode().value());
            verify(jobService).heartbeat("job-1");
        }

        @Test
        void missingSecret_returns403() {
            TranscodingHeartbeatRequest req = new TranscodingHeartbeatRequest("job-1");

            ResponseEntity<?> resp = controller.heartbeat(null, req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }

        @Test
        void wrongSecret_returns403() {
            TranscodingHeartbeatRequest req = new TranscodingHeartbeatRequest("job-1");

            ResponseEntity<?> resp = controller.heartbeat("wrong-secret", req);

            assertEquals(403, resp.getStatusCode().value());
            verifyNoInteractions(jobService);
        }
    }
}
