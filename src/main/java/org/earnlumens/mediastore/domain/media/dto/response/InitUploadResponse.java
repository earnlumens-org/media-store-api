package org.earnlumens.mediastore.domain.media.dto.response;

import java.util.List;

public record InitUploadResponse(
        String uploadId,
        String presignedUrl,
        String r2Key,
        boolean multipart,
        Long partSizeBytes,
        List<String> partUrls
) {
    /** Single-PUT upload (non-multipart). */
    public InitUploadResponse(String uploadId, String presignedUrl, String r2Key) {
        this(uploadId, presignedUrl, r2Key, false, null, null);
    }
}
