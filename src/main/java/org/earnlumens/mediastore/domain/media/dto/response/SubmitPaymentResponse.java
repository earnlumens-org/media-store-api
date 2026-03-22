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
        /** Entry ID that was unlocked (null for collection purchases) */
        String entryId,
        /** Collection ID that was unlocked (null for entry purchases) */
        String collectionId
) {}
