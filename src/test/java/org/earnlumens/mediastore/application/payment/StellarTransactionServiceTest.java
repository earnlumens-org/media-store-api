package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.*;
import org.stellar.sdk.requests.FeeStatsRequestBuilder;
import org.stellar.sdk.responses.FeeStatsResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StellarTransactionService}.
 *
 * Verifies:
 *   - resolveBaseFee uses fee_stats p90 when available
 *   - resolveBaseFee falls back to MIN_BASE_FEE on failure
 *   - resolveBaseFee never returns below MIN_BASE_FEE
 *   - buildTransaction produces valid XDR with correct ops count
 *   - buildTransaction uses dynamic fee from fee_stats
 *   - sha256Hex produces deterministic output
 */
class StellarTransactionServiceTest {

    private static final String TESTNET_PASSPHRASE = "Test SDF Network ; September 2015";

    // Generated once with KeyPair.random() — valid Stellar ed25519 addresses
    private String buyerPubkey;
    private String sellerWallet;
    private String platformWallet;

    private Server mockServer;
    private StellarConfig config;
    private StellarTransactionService service;

    @BeforeEach
    void setUp() {
        // Generate fresh valid Stellar keypairs for every test
        buyerPubkey   = KeyPair.random().getAccountId();
        sellerWallet  = KeyPair.random().getAccountId();
        platformWallet = KeyPair.random().getAccountId();

        mockServer = mock(Server.class);
        config = new StellarConfig();
        config.setHorizonUrl("https://horizon-testnet.stellar.org");
        config.setNetworkPassphrase(TESTNET_PASSPHRASE);
        config.setTxTimeoutSeconds(300);
        service = new StellarTransactionService(config, mockServer);
    }

    // ── resolveBaseFee (tested via buildTransaction) ─────────

    @Test
    void buildTransaction_usesLastLedgerBaseFee() throws Exception {
        // Arrange: fee_stats returns lastLedgerBaseFee = 200 stroops
        stubFeeStats(200L);
        stubLoadAccount();

        List<PaymentSplit> splits = twoWaySplits();
        BigDecimal totalXlm = new BigDecimal("5.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 5.00 XLM");

        // Assert: transaction built successfully
        assertNotNull(result.unsignedXdr());
        assertNotNull(result.integrityHash());
        assertNotNull(result.txHash());
        assertFalse(result.unsignedXdr().isBlank());

        // Decode the XDR to verify the fee = (200 + 10% = 220) × 2 ops = 440 stroops
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(440L, tx.getFee()); // 220 stroops/op × 2 ops
        assertEquals(2, tx.getOperations().length);
    }

    @Test
    void buildTransaction_fallsBackToMinBaseFee_whenFeeStatsFails() throws Exception {
        // Arrange: fee_stats throws (e.g. Horizon down)
        FeeStatsRequestBuilder feeBuilder = mock(FeeStatsRequestBuilder.class);
        when(feeBuilder.execute()).thenThrow(new RuntimeException("Horizon unavailable"));
        when(mockServer.feeStats()).thenReturn(feeBuilder);
        stubLoadAccount();

        List<PaymentSplit> splits = twoWaySplits();
        BigDecimal totalXlm = new BigDecimal("3.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 3.00 XLM");

        // Assert: falls back to MIN_BASE_FEE (100) × 2 ops = 200
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(200L, tx.getFee()); // 100 stroops/op × 2 ops
    }

    @Test
    void buildTransaction_neverGoesBelowMinBaseFee() throws Exception {
        // Arrange: fee_stats returns lastLedgerBaseFee = 50 stroops (below min)
        stubFeeStats(50L);
        stubLoadAccount();

        List<PaymentSplit> splits = twoWaySplits();
        BigDecimal totalXlm = new BigDecimal("1.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 1.00 XLM");

        // Assert: clamped to MIN_BASE_FEE (100) × 2 ops = 200
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(200L, tx.getFee()); // 100 stroops/op × 2 ops
    }

    @Test
    void buildTransaction_capsAtMaxBaseFee() throws Exception {
        // Arrange: fee_stats returns lastLedgerBaseFee = 60000 stroops (absurdly high, e.g. testnet)
        stubFeeStats(60_000L);
        stubLoadAccount();

        List<PaymentSplit> splits = twoWaySplits();
        BigDecimal totalXlm = new BigDecimal("1.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 1.00 XLM");

        // Assert: capped to MAX_BASE_FEE (1000) × 2 ops = 2000
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(2_000L, tx.getFee()); // 1000 stroops/op × 2 ops
    }

    // ── buildTransaction multi-split ──────────────────────────

    @Test
    void buildTransaction_handlesThreeSplits() throws Exception {
        // Arrange: 3-way split (platform + seller + collaborator)
        stubFeeStats(100L);
        stubLoadAccount();

        String collaboratorWallet = KeyPair.random().getAccountId();
        List<PaymentSplit> splits = List.of(
                new PaymentSplit(platformWallet, SplitRole.PLATFORM, new BigDecimal("10.00")),
                new PaymentSplit(sellerWallet, SplitRole.SELLER, new BigDecimal("80.00")),
                new PaymentSplit(collaboratorWallet, SplitRole.COLLABORATOR, new BigDecimal("10.00"))
        );
        BigDecimal totalXlm = new BigDecimal("10.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 10.00 XLM");

        // Assert: 3 ops, fee = (100 + 10% = 110) × 3 = 330
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(3, tx.getOperations().length);
        assertEquals(330L, tx.getFee()); // 110 stroops/op × 3 ops
    }

    @Test
    void buildTransaction_skipsZeroPercentSplits() throws Exception {
        // Arrange: one split has 0%
        stubFeeStats(100L);
        stubLoadAccount();

        List<PaymentSplit> splits = List.of(
                new PaymentSplit(platformWallet, SplitRole.PLATFORM, new BigDecimal("10.00")),
                new PaymentSplit(sellerWallet, SplitRole.SELLER, new BigDecimal("90.00")),
                new PaymentSplit("GDUMMY", SplitRole.COLLABORATOR, BigDecimal.ZERO)
        );
        BigDecimal totalXlm = new BigDecimal("5.00");

        // Act
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, totalXlm, splits, "TOTAL: 5.00 XLM");

        // Assert: only 2 ops (zero-amount split is skipped)
        Transaction tx = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                result.unsignedXdr(), new Network(TESTNET_PASSPHRASE));
        assertEquals(2, tx.getOperations().length);
    }

    // ── sha256Hex ─────────────────────────────────────────────

    @Test
    void sha256Hex_producesDeterministicOutput() {
        String input = "hello world";
        String hash1 = StellarTransactionService.sha256Hex(input);
        String hash2 = StellarTransactionService.sha256Hex(input);

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test
    void sha256Hex_differentInputsProduceDifferentHashes() {
        String hash1 = StellarTransactionService.sha256Hex("input-a");
        String hash2 = StellarTransactionService.sha256Hex("input-b");

        assertNotEquals(hash1, hash2);
    }

    // ── integrityHash determinism ─────────────────────────────

    @Test
    void buildTransaction_integrityHashMatchesSha256OfXdr() throws Exception {
        stubFeeStats(100L);
        stubLoadAccount();

        List<PaymentSplit> splits = twoWaySplits();
        StellarTransactionService.BuildResult result =
                service.buildTransaction(buyerPubkey, new BigDecimal("2.00"), splits, "TOTAL: 2.00 XLM");

        String expectedHash = StellarTransactionService.sha256Hex(result.unsignedXdr());
        assertEquals(expectedHash, result.integrityHash());
    }

    // ── Helpers ───────────────────────────────────────────────

    private List<PaymentSplit> twoWaySplits() {
        return List.of(
                new PaymentSplit(platformWallet, SplitRole.PLATFORM, new BigDecimal("10.00")),
                new PaymentSplit(sellerWallet, SplitRole.SELLER, new BigDecimal("90.00"))
        );
    }

    private void stubFeeStats(long lastLedgerBaseFee) {
        try {
            FeeStatsResponse statsResponse = mock(FeeStatsResponse.class);
            when(statsResponse.getLastLedgerBaseFee()).thenReturn(lastLedgerBaseFee);

            FeeStatsRequestBuilder feeBuilder = mock(FeeStatsRequestBuilder.class);
            when(feeBuilder.execute()).thenReturn(statsResponse);

            when(mockServer.feeStats()).thenReturn(feeBuilder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void stubLoadAccount() {
        try {
            // Create a real account object that the TransactionBuilder can work with
            Account account = new Account(buyerPubkey, 100L);
            doReturn(account).when(mockServer).loadAccount(buyerPubkey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
