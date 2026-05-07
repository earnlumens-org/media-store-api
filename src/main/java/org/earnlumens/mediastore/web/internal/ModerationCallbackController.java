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
import java.util.regex.Pattern;

/**
 * Internal callback endpoint for the Cloud Run moderation worker.
 * Protected by a shared secret header — only the worker should call this.
 */
@RestController
@RequestMapping("/api/internal")
public class ModerationCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(ModerationCallbackController.class);

    /** RFC-1123 subdomain label — same shape we accept as a tenant slug. */
    private static final Pattern TENANT_ID_PATTERN =
            Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    /**
     * Allowed jobId shape: alphanumerics plus '-' and '_' up to 64 chars.
     * Covers both 24-char Mongo ObjectIds (prod) and short test fixtures
     * ("mod-1"). Defence-in-depth against a worker compromise sending
     * crafted ids — even with a valid shared secret it cannot reach for
     * arbitrary documents through path-style payloads.
     */
    private static final Pattern JOB_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

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

        if (!isValidIds(request.tenantId(), request.jobId())) {
            logger.warn("Moderation callback: rejected — malformed tenantId/jobId (tenantId={}, jobId={})",
                    request.tenantId(), request.jobId());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId or jobId"));
        }

        String status = request.status().toUpperCase();

        return switch (status) {
            case "COMPLETED" -> {
                Optional<ModerationJob> job = moderationJobService.completeJob(
                        request.tenantId(), request.jobId(),
                        request.decision(), request.confidence(),
                        request.categoriesDetected(), request.reason(),
                        request.step(), request.detectedLanguage());
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

        if (!isValidIds(request.tenantId(), request.jobId())) {
            logger.warn("Moderation heartbeat: rejected — malformed tenantId/jobId");
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tenantId or jobId"));
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

    private static boolean isValidIds(String tenantId, String jobId) {
        return tenantId != null && jobId != null
                && TENANT_ID_PATTERN.matcher(tenantId).matches()
                && JOB_ID_PATTERN.matcher(jobId).matches();
    }
}
