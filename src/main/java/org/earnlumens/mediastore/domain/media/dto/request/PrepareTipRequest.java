package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request to prepare a TIP payment (voluntary creator support).
 * <p>
 * The frontend sends EITHER entryId or collectionId (the tipped content) plus
 * the buyer's wallet and the tip amount in USD. The amount is the ONLY price
 * input accepted from the client and is strictly bounded server-side; the
 * USD→XLM conversion, the recipient wallet and every split are resolved on the
 * backend (single source of truth). A tip grants no entitlement.
 */
public record PrepareTipRequest(
        /** Entry being tipped — required for entry tips, null for collection tips */
        String entryId,
        /** Collection being tipped — required for collection tips, null for entry tips */
        String collectionId,
        /** Franchise slug — set when the tip is sent through a franchise storefront (/f/&lt;slug&gt;), else null */
        String franchiseSlug,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String buyerWallet,
        /**
         * Tip amount in USD. Bounded to [0.25, 100.00] with at most 2 decimals.
         * Re-validated server-side regardless of these annotations.
         */
        @NotNull
        @DecimalMin(value = "0.25", message = "Tip amount must be at least $0.25")
        @DecimalMax(value = "100.00", message = "Tip amount must not exceed $100.00")
        @Digits(integer = 3, fraction = 2, message = "Tip amount supports at most 2 decimals")
        BigDecimal amountUsd
) {}
