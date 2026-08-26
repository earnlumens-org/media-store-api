package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request to prepare a PUBLISH_FEE payment (Publish Priority Fee).
 * <p>
 * Reorders the caller's queue item within its assigned publishing block: any
 * value above 0 XLM is valid, amounts are cumulative and can be increased
 * until the block locks (1 minute before publication). Fees are irreversible
 * and grant no entitlement.
 */
public record PreparePublishFeeRequest(
        /** The caller's QUEUED publishing queue item the fee applies to. */
        @NotBlank
        String queueItemId,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String buyerWallet,
        /** Fee amount in XLM — any positive value (requirement 6). Max 7 decimals (stroop precision). */
        @NotNull
        @DecimalMin(value = "0.0000001", message = "Fee must be greater than 0 XLM")
        @Digits(integer = 12, fraction = 7, message = "Fee supports at most 7 decimals")
        BigDecimal amountXlm
) {}
