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
        String kind
) {}
