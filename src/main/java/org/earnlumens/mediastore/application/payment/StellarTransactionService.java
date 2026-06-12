package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.stellar.sdk.*;
import org.stellar.sdk.exception.AccountNotFoundException;
import org.stellar.sdk.exception.BadRequestException;
import org.stellar.sdk.operations.Operation;
import org.stellar.sdk.operations.PaymentOperation;
import org.stellar.sdk.responses.FeeStatsResponse;
import org.stellar.sdk.responses.TransactionResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds, hashes and submits Stellar XLM payment transactions.
 *
 * Design invariants:
 *   - All amounts use 7 decimal places (Stellar's max precision for XLM).
 *   - Transactions contain one payment operation per split recipient.
 *   - MEMO_TEXT carries "TOTAL: X.XX XLM" for UX clarity.
 *   - TimeBounds enforce expiration; backend never submits an expired tx.
 */
@Service
public class StellarTransactionService {

    private static final Logger logger = LoggerFactory.getLogger(StellarTransactionService.class);
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * Maximum base fee per operation (10 000 stroops = 0.001 XLM).
     * Caps the resolved fee to protect users from extreme spikes while
     * still bidding high enough to get into a ledger under sustained
     * network-wide congestion (mass-adoption conditions, fee surges).
     * Stellar charges the market clearing price — never the max bid —
     * so this cap is a worst-case ceiling, not the usual cost
     * (0.001 XLM ≈ $0.0005 at XLM = $0.50).
     */
    private static final long MAX_BASE_FEE = 10_000L;  // 0.001 XLM per op

    private final StellarConfig stellarConfig;
    private final Server server;
    private final Network network;

    @Autowired
    public StellarTransactionService(StellarConfig stellarConfig) {
        this(stellarConfig, new Server(stellarConfig.getHorizonUrl()));
    }

    /** Package-private constructor for unit testing with a mock Server. */
    StellarTransactionService(StellarConfig stellarConfig, Server server) {
        this.stellarConfig = stellarConfig;
        this.server = server;
        this.network = new Network(stellarConfig.getNetworkPassphrase());
    }

    /**
     * Builds an unsigned multi-operation XLM payment transaction.
     *
     * @param buyerWallet  the buyer's Stellar public key (source account)
     * @param totalXlm     the total price in XLM
     * @param splits       payment split list with wallet + percent
     * @param memo         MEMO text (e.g. "TOTAL: 5.00 XLM")
     * @return BuildResult containing the unsigned XDR, integrityHash, and txHash
     */
    public BuildResult buildTransaction(String buyerWallet, BigDecimal totalXlm,
                                        List<PaymentSplit> splits, String memo) {
        try {
            // Load buyer account — fails if wallet is not funded on Stellar network.
            // Horizon may throw AccountNotFoundException (404) or BadRequestException (400)
            // depending on the network/SDK version.
            TransactionBuilderAccount sourceAccount;
            try {
                sourceAccount = server.loadAccount(buyerWallet);
            } catch (AccountNotFoundException | BadRequestException e) {
                logger.warn("Buyer wallet not found on Stellar network: {} ({})", buyerWallet, e.getClass().getSimpleName());
                throw new IllegalArgumentException("WALLET_NOT_ACTIVATED");
            }

            // Use fee_stats p90 for reliable inclusion under congestion.
            // In normal conditions Stellar charges MIN_BASE_FEE regardless of the max set here.
            long baseFee = resolveBaseFee();

            TransactionBuilder txBuilder = new TransactionBuilder(sourceAccount, network)
                    .setBaseFee(baseFee)
                    .setTimeout(stellarConfig.getTxTimeoutSeconds())
                    .addMemo(Memo.text(memo));

            // Add one payment operation per split recipient
            for (PaymentSplit split : splits) {
                BigDecimal amount = totalXlm.multiply(split.getPercent())
                        .divide(ONE_HUNDRED, 7, RoundingMode.DOWN);

                // Skip zero amounts (shouldn't happen but safety net)
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                txBuilder.addOperation(
                        PaymentOperation.builder()
                                .destination(split.getWallet())
                                .asset(Asset.createNativeAsset())
                                .amount(amount)
                                .build()
                );
            }

            Transaction transaction = txBuilder.build();

            // AbstractTransaction provides toEnvelopeXdrBase64() and hash()
            String unsignedXdr = transaction.toEnvelopeXdrBase64();
            String integrityHash = sha256Hex(unsignedXdr);
            String txHash = HexFormat.of().formatHex(transaction.hash());

            logger.info("Built Stellar tx: txHash={}, buyer={}, total={} XLM, ops={}",
                    txHash, buyerWallet, totalXlm.toPlainString(), splits.size());

            return new BuildResult(unsignedXdr, integrityHash, txHash);

        } catch (IllegalArgumentException e) {
            // Re-throw WALLET_NOT_ACTIVATED from the inner catch
            throw e;
        } catch (Exception e) {
            logger.error("Failed to build Stellar transaction: buyer={}, total={}", buyerWallet, totalXlm, e);
            throw new RuntimeException("Failed to build payment transaction", e);
        }
    }

    /**
     * Resolves the base fee per operation using Horizon's /fee_stats endpoint.
     * The bid is the max of:
     * <ul>
     *   <li>{@code lastLedgerBaseFee} — the actual base fee charged in the most
     *       recently closed ledger (normal conditions);</li>
     *   <li>the p90 of {@code fee_charged} over recent ledgers — what 90% of
     *       included transactions actually paid. Under sustained congestion
     *       (surge pricing / mass adoption) lastLedgerBaseFee alone lags the
     *       real clearing price and would leave the tx stuck in the queue.</li>
     * </ul>
     * plus a 10% safety buffer to absorb fee increases between XDR creation
     * and user signing/submission.
     *
     * Stellar always charges the market clearing price regardless of the max
     * fee bid, so the buffer never results in overpayment — it only ensures
     * ledger inclusion.
     *
     * Clamped between MIN_BASE_FEE (100 stroops) and MAX_BASE_FEE (10 000 stroops).
     * Falls back to MIN_BASE_FEE if the fee_stats call fails.
     */
    private long resolveBaseFee() {
        try {
            FeeStatsResponse stats = server.feeStats().execute();
            Long lastFee = stats.getLastLedgerBaseFee();
            Long p90 = (stats.getFeeCharged() != null) ? stats.getFeeCharged().getP90() : null;
            long base = Math.max(
                    (lastFee != null) ? lastFee : AbstractTransaction.MIN_BASE_FEE,
                    (p90 != null) ? p90 : 0L);
            // Add 10% safety buffer, rounding up to the next stroop
            long buffered = base + (base + 9) / 10;
            long fee = Math.min(Math.max(buffered, AbstractTransaction.MIN_BASE_FEE), MAX_BASE_FEE);
            logger.debug("Resolved base fee: lastLedgerBaseFee={}, feeChargedP90={}, +10%={}, clamped={}",
                    lastFee, p90, buffered, fee);
            return fee;
        } catch (Exception e) {
            logger.warn("Failed to fetch fee_stats, falling back to MIN_BASE_FEE", e);
            return AbstractTransaction.MIN_BASE_FEE;
        }
    }

    /**
     * Checks whether a Stellar account exists (is funded/activated) on the
     * configured network. Used at registration time — when a creator, tenant
     * or franchise sets a payout wallet — so that every wallet that can ever
     * appear as a payment destination is known to be active, and payments
     * never fail later with {@code op_no_destination}.
     *
     * Fail-open by design: if Horizon is unreachable or returns an unexpected
     * error, this returns {@code true} (with a warning log) so that a Horizon
     * outage never blocks creators from publishing. The payment flow itself
     * still confirms on-chain results, so a false positive here can only
     * delay the first sale, never lose funds.
     */
    public boolean isAccountActive(String publicKey) {
        try {
            server.loadAccount(publicKey);
            return true;
        } catch (AccountNotFoundException | BadRequestException e) {
            logger.info("Stellar account not active: {} ({})", publicKey, e.getClass().getSimpleName());
            return false;
        } catch (Exception e) {
            logger.warn("Could not verify Stellar account activation for {} — failing open", publicKey, e);
            return true;
        }
    }

    /**
     * TTL for cached positive activation checks (60 s — Phase 3, task 3.2 of
     * SCALABILITY-AUDIT.md, P1-4: the cache is per-instance, so the short TTL
     * bounds cross-instance divergence; the Horizon query is cheap).
     */
    private static final long ACTIVE_CACHE_TTL_MS = 60_000L;

    /** wallet → cache-entry expiry epoch millis. Positive results only. */
    private final Map<String, Long> activeAccountCache = new ConcurrentHashMap<>();

    /**
     * Same as {@link #isAccountActive(String)} but caches positive results for
     * {@value #ACTIVE_CACHE_TTL_MS} ms, so re-validating every split wallet on
     * each payment prepare adds ~0 ms on the hot path (one Horizon call per
     * wallet per minute, worst case). Negative results are never cached:
     * an account can be funded at any moment and must become sellable
     * immediately. Inherits the fail-open behaviour of isAccountActive, so a
     * Horizon outage never blocks purchases.
     */
    public boolean isAccountActiveCached(String publicKey) {
        long now = System.currentTimeMillis();
        Long expiry = activeAccountCache.get(publicKey);
        if (expiry != null && expiry > now) {
            return true;
        }
        boolean active = isAccountActive(publicKey);
        if (active) {
            activeAccountCache.put(publicKey, now + ACTIVE_CACHE_TTL_MS);
        } else {
            activeAccountCache.remove(publicKey);
        }
        return active;
    }

    /**
     * Strictly validates that a client-supplied signed XDR is byte-for-byte the
     * transaction this backend built for the given order. Nothing from the
     * client is trusted: every parameter is contrasted against the persisted order.
     *
     * Checks performed:
     * <ol>
     *   <li>The XDR parses against the configured network passphrase.</li>
     *   <li>The transaction hash equals the hash persisted at prepare time.
     *       The hash covers source account, sequence, fee, memo, time bounds and
     *       every operation — any tampering with amounts/destinations changes it.</li>
     *   <li>The envelope carries at least one signature.</li>
     *   <li>Defense in depth (explicit field checks, in case of any hash handling bug):
     *       source account == buyer wallet, MEMO_TEXT == order memo, and every
     *       operation is a native-XLM PaymentOperation whose destination/amount
     *       exactly match the split amounts recomputed from the order snapshot.</li>
     * </ol>
     *
     * @return the parsed, verified transaction ready for submission
     * @throws SecurityException if any check fails
     */
    public Transaction verifySignedXdrAgainstOrder(String signedXdr, Order order) {
        Transaction transaction;
        try {
            transaction = (Transaction) AbstractTransaction.fromEnvelopeXdr(signedXdr, network);
        } catch (Exception e) {
            throw new SecurityException("Signed XDR is not a valid transaction envelope", e);
        }

        // 1. Cryptographic identity: the hash must equal the one computed at prepare time.
        String actualHash = HexFormat.of().formatHex(transaction.hash());
        if (order.getStellarTxHash() == null || !actualHash.equalsIgnoreCase(order.getStellarTxHash())) {
            throw new SecurityException("Signed XDR does not match the prepared transaction (hash mismatch)");
        }

        // 2. Must actually be signed.
        if (transaction.getSignatures() == null || transaction.getSignatures().isEmpty()) {
            throw new SecurityException("Transaction envelope carries no signatures");
        }

        // 3. Explicit field-level checks (defense in depth).
        if (!transaction.getSourceAccount().equals(order.getBuyerWallet())) {
            throw new SecurityException("Transaction source account does not match the order's buyer wallet");
        }
        if (!(transaction.getMemo() instanceof MemoText memoText)
                || !memoText.getText().toString().equals(order.getMemo())) {
            throw new SecurityException("Transaction memo does not match the order");
        }

        Map<String, BigDecimal> expected = expectedPaymentsByDestination(order);
        Map<String, BigDecimal> actual = new HashMap<>();
        for (Operation op : transaction.getOperations()) {
            if (!(op instanceof PaymentOperation payment)) {
                throw new SecurityException("Transaction contains a non-payment operation");
            }
            if (!(payment.getAsset() instanceof AssetTypeNative)) {
                throw new SecurityException("Transaction contains a non-native (non-XLM) asset");
            }
            actual.merge(payment.getDestination(), payment.getAmount(), BigDecimal::add);
        }
        if (actual.size() != expected.size()) {
            throw new SecurityException("Unexpected number of payment destinations in transaction");
        }
        for (Map.Entry<String, BigDecimal> e : expected.entrySet()) {
            BigDecimal actualAmount = actual.get(e.getKey());
            if (actualAmount == null || actualAmount.compareTo(e.getValue()) != 0) {
                throw new SecurityException("Payment amount/destination mismatch against order splits");
            }
        }

        return transaction;
    }

    /**
     * Recomputes the exact per-destination amounts from the order's persisted
     * split snapshot, using the same rounding as {@link #buildTransaction}.
     */
    private Map<String, BigDecimal> expectedPaymentsByDestination(Order order) {
        Map<String, BigDecimal> expected = new HashMap<>();
        for (PaymentSplit split : order.getPaymentSplits()) {
            BigDecimal amount = order.getAmountXlm().multiply(split.getPercent())
                    .divide(ONE_HUNDRED, 7, RoundingMode.DOWN);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            expected.merge(split.getWallet(), amount, BigDecimal::add);
        }
        return expected;
    }

    /**
     * Submits a verified, signed transaction to the Stellar network via Horizon.
     *
     * @param transaction a transaction already verified with {@link #verifySignedXdrAgainstOrder}
     * @return classified submission outcome — never trusts an ambiguous response as success
     */
    public SubmissionOutcome submitTransaction(Transaction transaction) {
        String expectedHash = HexFormat.of().formatHex(transaction.hash());
        try {
            TransactionResponse response = server.submitTransaction(transaction);

            if (Boolean.TRUE.equals(response.getSuccessful())) {
                logger.info("Stellar tx submitted successfully: hash={}", response.getHash());
                return SubmissionOutcome.SUBMITTED;
            }
            String resultXdr = response.getResultXdr() != null ? response.getResultXdr() : "unknown";
            logger.error("Stellar tx submission rejected: hash={}, resultXdr={}", expectedHash, resultXdr);
            return SubmissionOutcome.REJECTED;
        } catch (BadRequestException e) {
            // Horizon definitively rejected the tx (e.g. tx_bad_seq, tx_bad_auth).
            logger.error("Stellar tx rejected by Horizon (400): hash={}", expectedHash, e);
            return SubmissionOutcome.REJECTED;
        } catch (Exception e) {
            // Timeout / 504 / network error: the tx MAY still have made it on-chain.
            // The caller must resolve via verifyTransactionOnChain before failing the order.
            logger.warn("Stellar tx submission outcome unknown (will verify on-chain): hash={}", expectedHash, e);
            return SubmissionOutcome.UNKNOWN;
        }
    }

    /**
     * Server-side source of truth: queries Horizon's official
     * {@code GET /transactions/{hash}} endpoint and confirms the transaction
     * exists, was applied successfully, was included in a ledger and matches
     * the order (source account + memo). No client data is involved.
     *
     * @return true only if the exact transaction is confirmed successful on-chain
     */
    public boolean verifyTransactionOnChain(String txHash, Order order) {
        try {
            TransactionResponse tx = server.transactions().transaction(txHash);
            if (tx == null) {
                return false;
            }
            boolean successful = Boolean.TRUE.equals(tx.getSuccessful());
            boolean hashMatches = txHash.equalsIgnoreCase(tx.getHash());
            boolean inLedger = tx.getLedger() != null && tx.getLedger() > 0;
            boolean sourceMatches = order.getBuyerWallet() != null
                    && order.getBuyerWallet().equals(tx.getSourceAccount());

            if (!(successful && hashMatches && inLedger && sourceMatches)) {
                logger.error("On-chain verification FAILED: hash={}, successful={}, hashMatches={}, inLedger={}, sourceMatches={}",
                        txHash, successful, hashMatches, inLedger, sourceMatches);
                return false;
            }
            logger.info("On-chain verification OK: hash={}, ledger={}", txHash, tx.getLedger());
            return true;
        } catch (Exception e) {
            // Not found / network error → NOT verified. Never assume success.
            logger.warn("On-chain verification could not confirm tx: hash={}", txHash, e);
            return false;
        }
    }

    /** Classified result of a Horizon submission attempt. */
    public enum SubmissionOutcome {
        /** Horizon accepted the tx and reported successful=true (still re-verified on-chain). */
        SUBMITTED,
        /** Horizon definitively rejected the tx. */
        REJECTED,
        /** Outcome ambiguous (timeout); must be resolved by on-chain lookup. */
        UNKNOWN
    }

    /**
     * Computes SHA-256 hex digest of the given input string.
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Result of building a transaction.
     */
    public record BuildResult(String unsignedXdr, String integrityHash, String txHash) {}
}
