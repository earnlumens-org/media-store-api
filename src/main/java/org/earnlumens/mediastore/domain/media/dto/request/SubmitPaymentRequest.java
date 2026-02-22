package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to submit a signed payment transaction.
 * The frontend sends the orderId and the signed XDR.
 */
public record SubmitPaymentRequest(
        @NotBlank String orderId,
        @NotBlank String signedXdr
) {}
