package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.TranscodingJobService;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.TranscodingHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.infrastructure.tenant.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
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
 *
 * <p>Also provides the batch-transcode endpoint for retroactively transcoding
 * existing video entries that were uploaded before the pipeline was implemented.
 */
@RestController
@RequestMapping("/api/internal")
public class TranscodingCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(TranscodingCallbackController.class);

    private final TranscodingJobService transcodingJobService;
    private final TenantResolver tenantResolver;
    private final String transcodingSecret;

    public TranscodingCallbackController(
            TranscodingJobService transcodingJobService,
            TenantResolver tenantResolver,
            @Value("${mediastore.internal.transcodingSecret}") String transcodingSecret
    ) {
        this.transcodingJobService = transcodingJobService;
        this.tenantResolver = tenantResolver;
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
                        request.jobId(), request.hlsR2Prefix(),
                        request.durationSec(), request.widthPx(), request.heightPx());
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

    /**
     * POST /api/internal/transcoding/batch
     * <p>
     * Creates PENDING transcoding jobs for all published VIDEO entries
     * that don't already have an active or completed transcoding job.
     * The existing watchdog + dispatcher pipeline will pick them up automatically.
     * <p>
     * Use this once to retroactively transcode videos uploaded before the HLS pipeline.
     */
    @PostMapping("/transcoding/batch")
    public ResponseEntity<?> batchTranscode(
            @RequestHeader(value = "X-Transcoding-Secret", required = false) String secret,
            HttpServletRequest request
    ) {
        if (secret == null || !transcodingSecret.equals(secret)) {
            logger.warn("Batch transcode: rejected — invalid or missing X-Transcoding-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String tenantId = tenantResolver.resolve(request);
        logger.info("Batch transcode: triggered for tenant={}", tenantId);

        int created = transcodingJobService.batchTranscodeExistingVideos(tenantId);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "jobsCreated", created
        ));
    }

    /**
     * POST /api/internal/metadata/batch
     * <p>
     * Fills missing durationSec on published VIDEO entries by copying
     * from the FULL asset's client-reported metadata.
     * <p>
     * Use this once for entries that were uploaded with metadata but whose
     * Entry never got durationSec denormalized.
     */
    @PostMapping("/metadata/batch")
    public ResponseEntity<?> batchMetadata(
            @RequestHeader(value = "X-Transcoding-Secret", required = false) String secret,
            HttpServletRequest request
    ) {
        if (secret == null || !transcodingSecret.equals(secret)) {
            logger.warn("Batch metadata: rejected — invalid or missing X-Transcoding-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String tenantId = tenantResolver.resolve(request);
        logger.info("Batch metadata: triggered for tenant={}", tenantId);

        int updated = transcodingJobService.batchFillMissingDuration(tenantId);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "entriesUpdated", updated
        ));
    }
}
