package org.earnlumens.mediastore.domain.media.dto.response;

public record CreateEntryResponse(
        String id,
        String title,
        String type,
        String status
) {}
