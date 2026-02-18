package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateEntryStatusRequest(

        @NotNull
        String status
) {}
