package org.earnlumens.mediastore.domain.media.dto.request;

import java.math.BigDecimal;

/**
 * Request body for PUT /api/collections/{id}.
 * All fields are optional; only non-null values are applied.
 */
public record UpdateCollectionRequest(
        String title,
        String description,
        String visibility,
        Boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        String sellerWallet
) {}
