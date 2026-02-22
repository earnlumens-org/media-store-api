package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * Response after successfully submitting a signed transaction.
 */
public record SubmitPaymentResponse(
        /** Order ID */
        String orderId,
        /** Stellar transaction hash (hex) */
        String stellarTxHash,
        /** Final order status (COMPLETED) */
        String status,
        /** Entry ID that was unlocked */
        String entryId
) {}
