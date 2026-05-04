package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body sent by the Cloud Run thumbnail worker when a job finishes.
 *
 * @param jobId            ThumbnailJob ID
 * @param tenantId         tenant scope for the job lookup
 * @param status           "COMPLETED" | "SKIPPED" | "FAILED"
 * @param variantsR2Prefix R2 prefix where variants were written (required if COMPLETED — must equal job.outputR2Prefix)
 * @param errorMessage     error description (required if FAILED) or skip reason (if SKIPPED)
 * @param sourceWidthPx    original image width in pixels (optional)
 * @param sourceHeightPx   original image height in pixels (optional)
 */
public record ThumbnailCallbackRequest(

        @NotBlank
        String jobId,

        @NotBlank
        String tenantId,

        @NotBlank
        String status,

        String variantsR2Prefix,

        String errorMessage,

        Integer sourceWidthPx,

        Integer sourceHeightPx
) {}
