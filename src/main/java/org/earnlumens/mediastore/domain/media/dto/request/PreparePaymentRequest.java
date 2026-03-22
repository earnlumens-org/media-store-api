package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to prepare a payment transaction.
 * The frontend sends EITHER entryId or collectionId (exactly one) plus the buyer's wallet.
 * All prices and splits are resolved server-side.
 */
public record PreparePaymentRequest(
        /** Entry ID — required for single-entry purchases, null for collection purchases */
        String entryId,
        /** Collection ID — required for collection purchases, null for entry purchases */
        String collectionId,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String buyerWallet
) {}
