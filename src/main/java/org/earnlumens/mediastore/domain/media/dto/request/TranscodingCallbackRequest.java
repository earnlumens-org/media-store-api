package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body sent by the Cloud Run transcoding worker when a job finishes.
 *
 * @param jobId       the TranscodingJob ID
 * @param status      "COMPLETED" or "FAILED"
 * @param hlsR2Prefix R2 prefix where HLS segments were written (required if COMPLETED)
 * @param errorMessage error description (required if FAILED)
 */
public record TranscodingCallbackRequest(

        @NotBlank
        String jobId,

        @NotBlank
        String status,

        /** R2 prefix for HLS output, e.g. "public/media/{entryId}/hls/" */
        String hlsR2Prefix,

        /** Error message from the worker (if status=FAILED) */
        String errorMessage
) {}
