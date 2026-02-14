package org.earnlumens.mediastore.domain.media.dto.response;

public record MediaEntitlementResponse(
        boolean allowed,
        String r2Key,
        String contentType,
        String contentDisposition,
        String fileName
) {}
