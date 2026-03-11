package org.earnlumens.mediastore.domain.media.dto.response;

import java.math.BigDecimal;

/**
 * Response after preparing a payment transaction.
 * Contains the unsigned XDR for the user to sign, plus metadata for the UI.
 */
public record PreparePaymentResponse(
        /** Order ID (used when submitting the signed tx) */
        String orderId,
        /** Base-64 encoded unsigned transaction envelope XDR */
        String unsignedXdr,
        /** SHA-256 hex of the unsigned XDR (integrity check) */
        String integrityHash,
        /** Total amount in XLM (this is what the Stellar tx will charge) */
        BigDecimal totalXlm,
        /** Original USD amount if entry priced in USD, null otherwise */
        BigDecimal originalAmountUsd,
        /** Price currency of the entry: "XLM" or "USD" */
        String priceCurrency,
        /** XLM/USD rate used for conversion (null for XLM-native prices) */
        BigDecimal xlmUsdRate,
        /** MEMO text embedded in the transaction */
        String memo,
        /** ISO-8601 expiration timestamp */
        String expiresAt,
        /** Stellar network passphrase (needed by wallets for signing) */
        String networkPassphrase
) {}
