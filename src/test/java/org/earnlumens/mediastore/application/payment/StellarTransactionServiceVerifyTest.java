package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.PaymentSplit;
import org.earnlumens.mediastore.domain.media.model.SplitRole;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.Account;
import org.stellar.sdk.Asset;
import org.stellar.sdk.AssetTypeCreditAlphaNum4;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Memo;
import org.stellar.sdk.Network;
import org.stellar.sdk.Server;
import org.stellar.sdk.TimeBounds;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilder;
import org.stellar.sdk.TransactionPreconditions;
import org.stellar.sdk.operations.PaymentOperation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link StellarTransactionService#verifySignedXdrAgainstOrder}
 * using real, offline-built Stellar transactions (no Horizon involved).
 * Every tampering vector must be rejected with a {@link SecurityException};
 * the only accepted XDR is byte-for-byte the transaction prepared for the order.
 */
class StellarTransactionServiceVerifyTest {

    private static final String PASSPHRASE = "Test SDF Network ; September 2015";
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final Network network = new Network(PASSPHRASE);
    private StellarTransactionService service;

    private KeyPair buyer;
    private String platformWallet;
    private String sellerWallet;
    private BigDecimal totalXlm;
    private List<PaymentSplit> splits;

    @BeforeEach
    void setUp() {
        StellarConfig config = new StellarConfig();
        config.setNetworkPassphrase(PASSPHRASE);
        service = new StellarTransactionService(config, mock(Server.class));

        buyer = KeyPair.random();
        platformWallet = KeyPair.random().getAccountId();
        sellerWallet = KeyPair.random().getAccountId();
        totalXlm = new BigDecimal("10");
        splits = List.of(
                new PaymentSplit(platformWallet, SplitRole.PLATFORM, new BigDecimal("10.00")),
                new PaymentSplit(sellerWallet, SplitRole.SELLER, new BigDecimal("90.00")));
    }

    /** Mirrors the per-split amount math of buildTransaction (7 decimals, DOWN). */
    private BigDecimal splitAmount(BigDecimal percent) {
        return totalXlm.multiply(percent).divide(ONE_HUNDRED, 7, RoundingMode.DOWN);
    }

    /** Builds the legitimate transaction exactly as prepare() would. */
    private Transaction buildLegitTransaction(String memo) {
        TransactionBuilder builder = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text(memo));
        for (PaymentSplit split : splits) {
            builder.addOperation(PaymentOperation.builder()
                    .destination(split.getWallet())
                    .asset(Asset.createNativeAsset())
                    .amount(splitAmount(split.getPercent()))
                    .build());
        }
        return builder.build();
    }

    /** Order snapshot matching the given transaction (hash set to the tx's real hash). */
    private Order orderFor(Transaction tx, String memo) {
        Order order = new Order();
        order.setId("order1");
        order.setBuyerWallet(buyer.getAccountId());
        order.setMemo(memo);
        order.setAmountXlm(totalXlm);
        order.setPaymentSplits(splits);
        order.setStellarTxHash(HexFormat.of().formatHex(tx.hash()));
        return order;
    }

    @Test
    void validSignedXdr_isAccepted() {
        Transaction tx = buildLegitTransaction("TOTAL: 10 XLM");
        Order order = orderFor(tx, "TOTAL: 10 XLM");
        tx.sign(buyer);

        Transaction verified = service.verifySignedXdrAgainstOrder(tx.toEnvelopeXdrBase64(), order);

        assertEquals(order.getStellarTxHash(), HexFormat.of().formatHex(verified.hash()));
    }

    @Test
    void unsignedXdr_isRejected() {
        Transaction tx = buildLegitTransaction("TOTAL: 10 XLM");
        Order order = orderFor(tx, "TOTAL: 10 XLM");
        // not signed

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tx.toEnvelopeXdrBase64(), order));
    }

    @Test
    void garbageXdr_isRejected() {
        Transaction tx = buildLegitTransaction("TOTAL: 10 XLM");
        Order order = orderFor(tx, "TOTAL: 10 XLM");

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder("not-an-xdr-envelope", order));
    }

    @Test
    void hashMismatch_isRejected() {
        // The order expects a different transaction than the one submitted —
        // e.g. a stale or swapped XDR from another prepare.
        Transaction tx = buildLegitTransaction("TOTAL: 10 XLM");
        Order order = orderFor(tx, "TOTAL: 10 XLM");
        order.setStellarTxHash("0".repeat(64));
        tx.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tx.toEnvelopeXdrBase64(), order));
    }

    @Test
    void nullPersistedHash_isRejected() {
        Transaction tx = buildLegitTransaction("TOTAL: 10 XLM");
        Order order = orderFor(tx, "TOTAL: 10 XLM");
        order.setStellarTxHash(null);
        tx.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tx.toEnvelopeXdrBase64(), order));
    }

    // ── Defense-in-depth field checks ────────────────────────────
    // These vectors forge the order's stored hash to match the tampered tx, so
    // the hash check alone would pass — the explicit field checks must catch them.

    @Test
    void tamperedMemo_isRejectedByFieldCheck() {
        Transaction tampered = buildLegitTransaction("TOTAL: 1 XLM"); // wrong memo
        Order order = orderFor(tampered, "TOTAL: 10 XLM");            // order keeps the real memo
        tampered.sign(buyer);

        SecurityException e = assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
        assertTrue(e.getMessage().contains("memo"));
    }

    @Test
    void tamperedAmount_isRejectedByFieldCheck() {
        Transaction tampered = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"))
                .addOperation(PaymentOperation.builder()
                        .destination(platformWallet)
                        .asset(Asset.createNativeAsset())
                        .amount(splitAmount(new BigDecimal("10.00")))
                        .build())
                .addOperation(PaymentOperation.builder()
                        .destination(sellerWallet)
                        .asset(Asset.createNativeAsset())
                        .amount(new BigDecimal("0.0000001")) // seller robbed
                        .build())
                .build();
        Order order = orderFor(tampered, "TOTAL: 10 XLM");
        tampered.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
    }

    @Test
    void redirectedDestination_isRejectedByFieldCheck() {
        String attackerWallet = KeyPair.random().getAccountId();
        Transaction tampered = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"))
                .addOperation(PaymentOperation.builder()
                        .destination(platformWallet)
                        .asset(Asset.createNativeAsset())
                        .amount(splitAmount(new BigDecimal("10.00")))
                        .build())
                .addOperation(PaymentOperation.builder()
                        .destination(attackerWallet) // seller payment redirected
                        .asset(Asset.createNativeAsset())
                        .amount(splitAmount(new BigDecimal("90.00")))
                        .build())
                .build();
        Order order = orderFor(tampered, "TOTAL: 10 XLM");
        tampered.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
    }

    @Test
    void extraOperation_isRejectedByFieldCheck() {
        Transaction tampered = buildTamperedWithExtraOp();
        Order order = orderFor(tampered, "TOTAL: 10 XLM");
        tampered.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
    }

    private Transaction buildTamperedWithExtraOp() {
        TransactionBuilder builder = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"));
        for (PaymentSplit split : splits) {
            builder.addOperation(PaymentOperation.builder()
                    .destination(split.getWallet())
                    .asset(Asset.createNativeAsset())
                    .amount(splitAmount(split.getPercent()))
                    .build());
        }
        builder.addOperation(PaymentOperation.builder()
                .destination(KeyPair.random().getAccountId()) // unexpected extra payment
                .asset(Asset.createNativeAsset())
                .amount(new BigDecimal("1.0000000"))
                .build());
        return builder.build();
    }

    @Test
    void nonNativeAsset_isRejectedByFieldCheck() {
        Asset usdAsset = new AssetTypeCreditAlphaNum4("USD", KeyPair.random().getAccountId());
        Transaction tampered = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"))
                .addOperation(PaymentOperation.builder()
                        .destination(platformWallet)
                        .asset(usdAsset) // not XLM
                        .amount(splitAmount(new BigDecimal("10.00")))
                        .build())
                .addOperation(PaymentOperation.builder()
                        .destination(sellerWallet)
                        .asset(Asset.createNativeAsset())
                        .amount(splitAmount(new BigDecimal("90.00")))
                        .build())
                .build();
        Order order = orderFor(tampered, "TOTAL: 10 XLM");
        tampered.sign(buyer);

        assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
    }

    @Test
    void wrongSourceAccount_isRejectedByFieldCheck() {
        KeyPair impostor = KeyPair.random();
        TransactionBuilder builder = new TransactionBuilder(new Account(impostor.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"));
        for (PaymentSplit split : splits) {
            builder.addOperation(PaymentOperation.builder()
                    .destination(split.getWallet())
                    .asset(Asset.createNativeAsset())
                    .amount(splitAmount(split.getPercent()))
                    .build());
        }
        Transaction tampered = builder.build();
        Order order = orderFor(tampered, "TOTAL: 10 XLM"); // buyerWallet is still the real buyer
        tampered.sign(impostor);

        SecurityException e = assertThrows(SecurityException.class,
                () -> service.verifySignedXdrAgainstOrder(tampered.toEnvelopeXdrBase64(), order));
        assertTrue(e.getMessage().contains("source account"));
    }

    // ── Cross-SDK golden vector (MYCELIUM-RESILIENCE.md §0.0, P30-1) ──
    // Envelope, hash and signature emitted by stellar-sdk 2.2.3 for a fixed 3-way
    // split. Orders prepared by a revision on the old SDK are verified by the new one.

    private static final String GOLDEN_UNSIGNED_XDR =
            "AAAAAgAAAACKiOPddAnxlf1S2y08ul1yymcJvx2UEhvzdIgBtA9vXAAAApQAAAAAB1vNFgAAAAEAAAAAAAAAAAAAAABrSdIAAAAAAQAAAA9UT1RBTDogNy43NyBYTE0AAAAAAwAAAAAAAAABAAAAAIE5dw6ofRdfVqNUZsNMfszLjYqRtO43ol32D1uPybOUAAAAAAAAAAAAdo+QAAAAAAAAAAEAAAAA7UkoxijRwsbq6QM4kFmVYSlZJzpcY/k2NsFGFKyHN9EAAAAAAAAAAAOW2JwAAAAAAAAAAQAAAADKk6wXBRhwcdZ7g8f/Dv6BCOjsRTBXXXcmh5Mz29q+fAAAAAAAAAAAAJQzdAAAAAAAAAAA";
    private static final String GOLDEN_TX_HASH =
            "d8cb1d3fe058e3fd2faa27e3de4ba62757c54cc17c3a7ffef525ddbe38ed40cf";
    private static final String GOLDEN_SIGNED_XDR =
            "AAAAAgAAAACKiOPddAnxlf1S2y08ul1yymcJvx2UEhvzdIgBtA9vXAAAApQAAAAAB1vNFgAAAAEAAAAAAAAAAAAAAABrSdIAAAAAAQAAAA9UT1RBTDogNy43NyBYTE0AAAAAAwAAAAAAAAABAAAAAIE5dw6ofRdfVqNUZsNMfszLjYqRtO43ol32D1uPybOUAAAAAAAAAAAAdo+QAAAAAAAAAAEAAAAA7UkoxijRwsbq6QM4kFmVYSlZJzpcY/k2NsFGFKyHN9EAAAAAAAAAAAOW2JwAAAAAAAAAAQAAAADKk6wXBRhwcdZ7g8f/Dv6BCOjsRTBXXXcmh5Mz29q+fAAAAAAAAAAAAJQzdAAAAAAAAAABtA9vXAAAAEBEs+T4SvGSJXd/po7kvk89PwfSysF7dPuMbZLhdyLYJR8p9mMBLTMrZ6SyjrAENyQ6vm7O7htPYgioL8BSiPcJ";

    private static KeyPair fixedKeyPair(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return KeyPair.fromSecretSeed(seed);
    }

    @Test
    void envelopeFromSdk2_2_3_isReproducedByteForByteAndStillVerifies() {
        buyer = fixedKeyPair(1);
        totalXlm = new BigDecimal("7.77");
        splits = List.of(
                new PaymentSplit(fixedKeyPair(2).getAccountId(), SplitRole.PLATFORM, new BigDecimal("10.00")),
                new PaymentSplit(fixedKeyPair(3).getAccountId(), SplitRole.SELLER, new BigDecimal("77.50")),
                new PaymentSplit(fixedKeyPair(4).getAccountId(), SplitRole.COLLABORATOR, new BigDecimal("12.50")));
        String memo = "TOTAL: 7.77 XLM";

        TransactionBuilder builder = new TransactionBuilder(new Account(buyer.getAccountId(), 123456789L), network)
                .setBaseFee(220)
                .addPreconditions(TransactionPreconditions.builder()
                        .timeBounds(new TimeBounds(0L, 1_800_000_000L)).build())
                .addMemo(Memo.text(memo));
        for (PaymentSplit split : splits) {
            builder.addOperation(PaymentOperation.builder()
                    .destination(split.getWallet())
                    .asset(Asset.createNativeAsset())
                    .amount(splitAmount(split.getPercent()))
                    .build());
        }
        Transaction rebuilt = builder.build();

        assertEquals(GOLDEN_UNSIGNED_XDR, rebuilt.toEnvelopeXdrBase64());
        assertEquals(GOLDEN_TX_HASH, HexFormat.of().formatHex(rebuilt.hash()));
        rebuilt.sign(buyer);
        assertEquals(GOLDEN_SIGNED_XDR, rebuilt.toEnvelopeXdrBase64());

        Order order = orderFor(rebuilt, memo);
        order.setStellarTxHash(GOLDEN_TX_HASH);
        Transaction verified = service.verifySignedXdrAgainstOrder(GOLDEN_SIGNED_XDR, order);

        assertEquals(GOLDEN_TX_HASH, HexFormat.of().formatHex(verified.hash()));
        assertEquals(GOLDEN_SIGNED_XDR, verified.toEnvelopeXdrBase64());
    }
}
