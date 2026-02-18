package org.earnlumens.mediastore.domain.media.dto.response;

public record InitUploadResponse(
        String uploadId,
        String presignedUrl,
        String r2Key
) {}
