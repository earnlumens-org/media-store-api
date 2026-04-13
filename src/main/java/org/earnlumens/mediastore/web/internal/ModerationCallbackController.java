package org.earnlumens.mediastore.web.internal;

import org.earnlumens.mediastore.application.media.ModerationJobService;
import org.earnlumens.mediastore.domain.media.dto.request.ModerationCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.ModerationHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.model.ModerationJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;

/**
 * Internal callback endpoint for the Cloud Run moderation worker.
 * Protected by a shared secret header — only the worker should call this.
 */
@RestController
@RequestMapping("/api/internal")
public class ModerationCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(ModerationCallbackController.class);

    private final ModerationJobService moderationJobService;
    private final String moderationSecret;

    public ModerationCallbackController(
            ModerationJobService moderationJobService,
            @Value("${mediastore.internal.moderationSecret}") String moderationSecret
    ) {
        this.moderationJobService = moderationJobService;
        this.moderationSecret = moderationSecret;
    }

    /**
     * POST /api/internal/moderation/complete
     * <p>
     * Called by the Cloud Run moderation worker when a job finishes.
     * Requires header: X-Moderation-Secret matching the configured secret.
     */
    @PostMapping("/moderation/complete")
    public ResponseEntity<?> handleCallback(
            @RequestHeader(value = "X-Moderation-Secret", required = false) String secret,
            @Valid @RequestBody ModerationCallbackRequest request
    ) {
        if (secret == null || !moderationSecret.equals(secret)) {
            logger.warn("Moderation callback: rejected — invalid or missing X-Moderation-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String status = request.status().toUpperCase();

        return switch (status) {
            case "COMPLETED" -> {
                Optional<ModerationJob> job = moderationJobService.completeJob(
                        request.tenantId(), request.jobId(),
                        request.decision(), request.confidence(),
                        request.categoriesDetected(), request.reason(),
                        request.step());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of(
                                "status", "COMPLETED",
                                "jobId", request.jobId(),
                                "decision", job.get().getDecision().name()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found"));
            }
            case "FAILED" -> {
                Optional<ModerationJob> job = moderationJobService.failJob(
                        request.tenantId(), request.jobId(), request.errorMessage());
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
     * POST /api/internal/moderation/heartbeat
     * <p>
     * Called periodically by the Cloud Run worker to prove it's still alive.
     * The first heartbeat transitions the job from DISPATCHED → PROCESSING.
     */
    @PostMapping("/moderation/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestHeader(value = "X-Moderation-Secret", required = false) String secret,
            @RequestBody ModerationHeartbeatRequest request
    ) {
        if (secret == null || !moderationSecret.equals(secret)) {
            logger.warn("Moderation heartbeat: rejected — invalid or missing X-Moderation-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        moderationJobService.heartbeat(request.jobId(), request.tenantId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * GET /api/internal/moderation/status
     * <p>
     * Returns a summary of moderation jobs by status. Useful for monitoring.
     */
    @GetMapping("/moderation/status")
    public ResponseEntity<?> jobStatus(
            @RequestHeader(value = "X-Moderation-Secret", required = false) String secret
    ) {
        if (secret == null || !moderationSecret.equals(secret)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        var pending = moderationJobService.findByStatus(ModerationJobStatus.PENDING);
        var dispatched = moderationJobService.findByStatus(ModerationJobStatus.DISPATCHED);
        var processing = moderationJobService.findByStatus(ModerationJobStatus.PROCESSING);
        var completed = moderationJobService.findByStatus(ModerationJobStatus.COMPLETED);
        var dead = moderationJobService.findByStatus(ModerationJobStatus.DEAD);

        return ResponseEntity.ok(Map.of(
                "PENDING", pending.size(),
                "DISPATCHED", dispatched.size(),
                "PROCESSING", processing.size(),
                "COMPLETED", completed.size(),
                "DEAD", dead.size()
        ));
    }
}
