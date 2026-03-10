package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FinalizeUploadRequest(

        @NotBlank
        String uploadId,

        @NotBlank
        String entryId,

        @NotBlank
        String r2Key,

        @NotBlank
        String contentType,

        @NotBlank
        String fileName,

        @NotNull
        @Positive
        Long fileSizeBytes,

        @NotNull
        String kind,

        // ── Client-extracted media metadata (nullable — best-effort from browser) ──

        /** Video/image width in pixels (from HTMLVideoElement.videoWidth or naturalWidth) */
        Integer widthPx,

        /** Video/image height in pixels (from HTMLVideoElement.videoHeight or naturalHeight) */
        Integer heightPx,

        /** Duration in seconds (from HTMLVideoElement.duration or HTMLAudioElement.duration) */
        Integer durationSec,

        /** Video codec string if detectable (e.g. "avc1.42E01E") — reserved for future server-side probe */
        String codecVideo,

        /** Audio codec string if detectable (e.g. "mp4a.40.2") — reserved for future server-side probe */
        String codecAudio,

        /** Approximate bitrate in bits per second (fileSizeBytes * 8 / durationSec) */
        Long bitrateBps
) {}
