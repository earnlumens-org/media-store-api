package org.earnlumens.mediastore.domain.media.dto.request;

import java.math.BigDecimal;

/**
 * Request body for PUT /api/entries/{id} — update entry metadata.
 * All fields are optional; only non-null values are applied.
 */
public record UpdateEntryMetadataRequest(
        String title,
        String description,
        Boolean isPaid,
        BigDecimal priceXlm
) {}
