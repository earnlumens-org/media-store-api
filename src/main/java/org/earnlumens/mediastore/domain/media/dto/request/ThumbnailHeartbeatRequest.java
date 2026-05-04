package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Periodic heartbeat from the thumbnail worker proving liveness. */
public record ThumbnailHeartbeatRequest(

        @NotBlank
        String jobId,

        @NotBlank
        String tenantId
) {}
