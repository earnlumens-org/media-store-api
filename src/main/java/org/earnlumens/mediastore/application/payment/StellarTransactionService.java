package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.stellar.sdk.*;
import org.stellar.sdk.exception.AccountNotFoundException;
import org.stellar.sdk.exception.BadRequestException;
import org.stellar.sdk.operations.PaymentOperation;
import org.stellar.sdk.responses.FeeStatsResponse;
import org.stellar.sdk.responses.TransactionResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

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
     * Maximum base fee per operation (1 000 stroops = 0.0001 XLM).
     * Caps the resolved fee to protect users from extreme spikes.
     * 10× MIN_BASE_FEE is enough for severe congestion while keeping
     * the fee shown in wallets at sane levels ($0.01 at XLM = $100).
     */
    private static final long MAX_BASE_FEE = 1_000L;  // 0.0001 XLM per op

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
     * Uses {@code lastLedgerBaseFee} — the actual base fee from the most recently
     * closed ledger — plus a 10% safety buffer to absorb minor fee increases
     * between XDR creation and user signing/submission.
     *
     * Stellar always charges the minimum needed regardless of the max fee set,
     * so the buffer never results in overpayment — it only ensures inclusion.
     *
     * Clamped between MIN_BASE_FEE (100 stroops) and MAX_BASE_FEE (1 000 stroops).
     * Falls back to MIN_BASE_FEE if the fee_stats call fails.
     */
    private long resolveBaseFee() {
        try {
            FeeStatsResponse stats = server.feeStats().execute();
            Long lastFee = stats.getLastLedgerBaseFee();
            long base = (lastFee != null) ? lastFee : AbstractTransaction.MIN_BASE_FEE;
            // Add 10% safety buffer, rounding up to the next stroop
            long buffered = base + (base + 9) / 10;
            long fee = Math.min(Math.max(buffered, AbstractTransaction.MIN_BASE_FEE), MAX_BASE_FEE);
            logger.debug("Resolved base fee: lastLedgerBaseFee={}, +10%={}, clamped={}", lastFee, buffered, fee);
            return fee;
        } catch (Exception e) {
            logger.warn("Failed to fetch fee_stats, falling back to MIN_BASE_FEE", e);
            return AbstractTransaction.MIN_BASE_FEE;
        }
    }

    /**
     * Submits a signed transaction to the Stellar network via Horizon.
     *
     * @param signedXdr the signed transaction envelope XDR (base-64)
     * @return the transaction hash on success
     * @throws RuntimeException if the submission fails
     */
    public String submitTransaction(String signedXdr) {
        try {
            // AbstractTransaction.fromEnvelopeXdr(String, Network) returns AbstractTransaction
            Transaction transaction = (Transaction) AbstractTransaction.fromEnvelopeXdr(signedXdr, network);

            // SDK 2.x: submitTransaction returns TransactionResponse, throws on network error
            TransactionResponse response = server.submitTransaction(transaction);

            if (Boolean.TRUE.equals(response.getSuccessful())) {
                logger.info("Stellar tx submitted successfully: hash={}", response.getHash());
                return response.getHash();
            } else {
                String resultXdr = response.getResultXdr() != null
                        ? response.getResultXdr()
                        : "unknown";
                logger.error("Stellar tx submission failed: resultXdr={}", resultXdr);
                throw new RuntimeException("Stellar transaction failed: " + resultXdr);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to submit Stellar transaction", e);
            throw new RuntimeException("Failed to submit transaction to Stellar network", e);
        }
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
