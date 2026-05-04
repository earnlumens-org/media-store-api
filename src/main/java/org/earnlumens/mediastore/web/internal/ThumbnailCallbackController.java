package org.earnlumens.mediastore.web.internal;

import jakarta.validation.Valid;
import org.earnlumens.mediastore.application.media.ThumbnailJobService;
import org.earnlumens.mediastore.domain.media.dto.request.ThumbnailCallbackRequest;
import org.earnlumens.mediastore.domain.media.dto.request.ThumbnailHeartbeatRequest;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
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

import java.util.Map;
import java.util.Optional;

/**
 * Internal callback endpoints for the Cloud Run thumbnail worker.
 *
 * <p>Protected by a shared secret header — only the worker should call this.
 *
 * <p>The worker calls this endpoint when a job finishes:
 * <ul>
 *   <li>{@code status=COMPLETED} → variantsR2Prefix denormalised onto Entry/Collection</li>
 *   <li>{@code status=SKIPPED}   → input below min-size; original is used as-is</li>
 *   <li>{@code status=FAILED}    → job retried or marked DEAD (graceful — original used)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/internal/thumbnail")
public class ThumbnailCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailCallbackController.class);

    private final ThumbnailJobService thumbnailJobService;
    private final String thumbnailSecret;

    public ThumbnailCallbackController(
            ThumbnailJobService thumbnailJobService,
            @Value("${mediastore.internal.thumbnailSecret}") String thumbnailSecret
    ) {
        this.thumbnailJobService = thumbnailJobService;
        this.thumbnailSecret = thumbnailSecret;
    }

    @PostMapping("/complete")
    public ResponseEntity<?> handleCallback(
            @RequestHeader(value = "X-Thumbnail-Secret", required = false) String secret,
            @Valid @RequestBody ThumbnailCallbackRequest request
    ) {
        if (secret == null || !thumbnailSecret.equals(secret)) {
            logger.warn("Thumbnail callback: rejected — invalid or missing X-Thumbnail-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String status = request.status().toUpperCase();

        return switch (status) {
            case "COMPLETED" -> {
                if (request.variantsR2Prefix() == null || request.variantsR2Prefix().isBlank()) {
                    yield ResponseEntity.badRequest().body(
                            Map.of("error", "variantsR2Prefix is required when status=COMPLETED"));
                }
                Optional<ThumbnailJob> job = thumbnailJobService.completeJob(
                        request.tenantId(), request.jobId(), request.variantsR2Prefix(),
                        request.sourceWidthPx(), request.sourceHeightPx());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of("status", "COMPLETED", "jobId", request.jobId()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found or prefix mismatch"));
            }
            case "SKIPPED" -> {
                Optional<ThumbnailJob> job = thumbnailJobService.skipJob(
                        request.tenantId(), request.jobId(),
                        request.errorMessage() == null ? "Below minimum size" : request.errorMessage(),
                        request.sourceWidthPx(), request.sourceHeightPx());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of("status", "SKIPPED", "jobId", request.jobId()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found"));
            }
            case "FAILED" -> {
                Optional<ThumbnailJob> job = thumbnailJobService.failJob(
                        request.tenantId(), request.jobId(), request.errorMessage());
                yield job.isPresent()
                        ? ResponseEntity.ok(Map.of(
                                "status", job.get().getStatus().name(),
                                "jobId", request.jobId()))
                        : ResponseEntity.status(404).body(Map.of("error", "Job not found"));
            }
            default -> ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid status: must be COMPLETED, SKIPPED or FAILED"));
        };
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(
            @RequestHeader(value = "X-Thumbnail-Secret", required = false) String secret,
            @RequestBody ThumbnailHeartbeatRequest request
    ) {
        if (secret == null || !thumbnailSecret.equals(secret)) {
            logger.warn("Thumbnail heartbeat: rejected — invalid or missing X-Thumbnail-Secret");
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        thumbnailJobService.heartbeat(request.tenantId(), request.jobId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/status")
    public ResponseEntity<?> jobStatus(
            @RequestHeader(value = "X-Thumbnail-Secret", required = false) String secret
    ) {
        if (secret == null || !thumbnailSecret.equals(secret)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        return ResponseEntity.ok(Map.of(
                "PENDING",    thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.PENDING).size(),
                "DISPATCHED", thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.DISPATCHED).size(),
                "PROCESSING", thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.PROCESSING).size(),
                "COMPLETED",  thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.COMPLETED).size(),
                "SKIPPED",    thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.SKIPPED).size(),
                "DEAD",       thumbnailJobService.findByStatus(
                        org.earnlumens.mediastore.domain.media.model.ThumbnailJobStatus.DEAD).size()
        ));
    }
}
