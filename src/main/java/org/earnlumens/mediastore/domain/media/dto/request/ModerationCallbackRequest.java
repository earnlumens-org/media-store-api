package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request body sent by the Cloud Run moderation worker when a job finishes.
 *
 * @param jobId              the ModerationJob ID
 * @param tenantId           tenant ID for scoping the job lookup
 * @param status             "COMPLETED" or "FAILED"
 * @param decision           moderation decision: APPROVE, REJECT, or MANUAL_QUEUE
 * @param confidence         confidence score (0.0–1.0)
 * @param categoriesDetected categories that triggered the decision
 * @param reason             human-readable explanation
 * @param step               which pipeline step produced the decision
 * @param errorMessage       error description (if status=FAILED)
 */
public record ModerationCallbackRequest(

        @NotBlank
        String jobId,

        @NotBlank
        String tenantId,

        @NotBlank
        String status,

        String decision,
        Double confidence,
        List<String> categoriesDetected,
        String reason,
        String step,
        String errorMessage
) {}
