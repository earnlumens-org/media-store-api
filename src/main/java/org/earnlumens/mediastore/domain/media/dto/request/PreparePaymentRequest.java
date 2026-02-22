package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to prepare a payment transaction.
 * The frontend only sends the content ID and the buyer's wallet address.
 * All prices and splits are resolved server-side from the Entry.
 */
public record PreparePaymentRequest(
        @NotBlank String entryId,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String buyerWallet
) {}
