package org.earnlumens.mediastore.domain.media.dto.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for PUT /api/entries/{id} — update entry metadata.
 * All fields are optional; only non-null values are applied.
 */
public record UpdateEntryMetadataRequest(
        String title,
        String description,
        Boolean isPaid,
        BigDecimal priceXlm,
        BigDecimal priceUsd,
        String priceCurrency,
        String sellerWallet,
        String resourceContent,
        /**
         * Replacement list of spaceIds. {@code null} = leave unchanged;
         * empty list = clear all spaces; non-empty = replace with the
         * validated set (see {@code SpaceValidationService}).
         */
        List<String> spaceIds
) {}
