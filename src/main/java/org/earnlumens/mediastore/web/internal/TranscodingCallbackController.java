package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.TranscodingJobService;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;

/**
 * Internal callback endpoint for the Cloud Run transcoding worker.
 * Protected by a shared secret header — only the worker should call this.
 *
 * <p>The worker calls this endpoint when a transcoding job finishes:
 * <ul>
 *   <li>status=COMPLETED → asset transitions to READY, HLS prefix is recorded</li>
 *   <li>status=FAILED → job is retried or marked DEAD (with user notification)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal")
public class TranscodingCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(TranscodingCallbackController.class);

    private final TranscodingJobService transcodingJobService;
    private final String transcodingSecret;

    public TranscodingCallbackController(
            TranscodingJobService transcodingJobService,
            @Value("${mediastore.internal.transcodingSecret}") String transcodingSecret
    ) {
        this.transcodingJobService = transcodingJobService;
        this.transcodingSecret = transcodingSecret;
    }

    /**
     * POST /api/internal/transcoding/complete
     * <p>
     * Called by the Cloud Run transcoding worker when a job finishes.
     * Requires header: X-Transcoding-Secret matching the configured secret.
     */
    @PostMapping("/transcoding/complete")
    public ResponseEntity<?> handleCallback(
            @RequestHeader(value = "X-Transcoding-Secret", required = false) String secret,
            @Valid @RequestBody TranscodingCallbackRequest request
    ) {
        if (secret == null || !transcodingSecret.equals(secret)) {
            logger.warn("Transcoding callback: rejected — invalid or missing X-Transcoding-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String status = request.status().toUpperCase();

        return switch (status) {
            case "COMPLETED" -> {
                if (request.hlsR2Prefix() == null || request.hlsR2Prefix().isBlank()) {
                    yield ResponseEntity.badRequest().body(
                            Map.of("error", "hlsR2Prefix is required when status=COMPLETED"));
                }
                Optional<TranscodingJob> job = transcodingJobService.completeJob(
                        request.jobId(), request.hlsR2Prefix());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of("status", "COMPLETED", "jobId", request.jobId()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found"));
            }
            case "FAILED" -> {
                Optional<TranscodingJob> job = transcodingJobService.failJob(
                        request.jobId(), request.errorMessage());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of(
                                "status", job.get().getStatus().name(),
                                "jobId", request.jobId()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found"));
            }
            default -> ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid status: must be COMPLETED or FAILED"));
        };
    }

    /**
     * POST /api/internal/transcoding/heartbeat
     * <p>
     * Called periodically by the Cloud Run worker to prove it's still alive.
     * The first heartbeat transitions the job from DISPATCHED → PROCESSING.
     */
    @PostMapping("/transcoding/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestHeader(value = "X-Transcoding-Secret", required = false) String secret,
            @RequestBody TranscodingHeartbeatRequest request
    ) {
        if (secret == null || !transcodingSecret.equals(secret)) {
            logger.warn("Transcoding heartbeat: rejected — invalid or missing X-Transcoding-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        transcodingJobService.heartbeat(request.jobId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
