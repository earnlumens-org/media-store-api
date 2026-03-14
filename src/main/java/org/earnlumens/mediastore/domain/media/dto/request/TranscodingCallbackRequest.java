package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body sent by the Cloud Run transcoding worker when a job finishes.
 *
 * @param jobId        the TranscodingJob ID
 * @param status       "COMPLETED" or "FAILED"
 * @param hlsR2Prefix  R2 prefix where HLS segments were written (required if COMPLETED)
 * @param errorMessage error description (required if FAILED)
 * @param durationSec  video duration in seconds (from ffprobe, optional)
 * @param widthPx      source video width in pixels (from ffprobe, optional)
 * @param heightPx     source video height in pixels (from ffprobe, optional)
 */
public record TranscodingCallbackRequest(

        @NotBlank
        String jobId,

        @NotBlank
        String status,

        /** Tenant ID for scoping the job lookup. */
        @NotBlank
        String tenantId,

        /** R2 prefix for HLS output, e.g. "public/media/{entryId}/hls/" */
        String hlsR2Prefix,

        /** Error message from the worker (if status=FAILED) */
        String errorMessage,

        /** Video duration in seconds, extracted by ffprobe during transcoding. */
        Integer durationSec,

        /** Source video width in pixels, extracted by ffprobe. */
        Integer widthPx,

        /** Source video height in pixels, extracted by ffprobe. */
        Integer heightPx
) {}
