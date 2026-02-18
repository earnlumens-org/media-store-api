package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InitUploadRequest(

        @NotBlank
        String entryId,

        @NotBlank
        String fileName,

        @NotBlank
        String contentType,

        @NotNull
        String kind,

        @NotNull
        @Positive
        Long fileSizeBytes
) {}
