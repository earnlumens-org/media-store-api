package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.stellar.sdk.Account;
import org.stellar.sdk.Asset;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Memo;
import org.stellar.sdk.Network;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilder;
import org.stellar.sdk.operations.PaymentOperation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the hardened {@link PaymentService#submit} pipeline:
 * atomic PENDING→PROCESSING lock, signed-XDR verification, anti-replay,
 * classified Horizon submission, mandatory on-chain confirmation, CAS
 * completion and idempotent entitlement creation.
 */
class PaymentServiceSubmitTest {

    private static final String TENANT = "earnlumens";
    private static final String USER = "user1";
    private static final String ORDER_ID = "order1";
    private static final String TX_HASH = "aabbccdd00112233aabbccdd00112233aabbccdd00112233aabbccdd00112233";
    private static final String SIGNED_XDR = "AAAA-signed-xdr";
    private static final SubmitPaymentRequest REQUEST = new SubmitPaymentRequest(ORDER_ID, SIGNED_XDR);

    private OrderRepository orderRepository;
    private EntitlementRepository entitlementRepository;
    private StellarTransactionService stellarTxService;
    private PaymentService service;
    private Transaction parsedTx;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        stellarTxService = mock(StellarTransactionService.class);
        service = new PaymentService(
                null, null, orderRepository, entitlementRepository,
                stellarTxService, new StellarConfig(), null, null, null, null);
        parsedTx = buildDummyTransaction();
    }

    @AfterEach
    void clearInterruptFlag() {
        // Some tests interrupt the current thread to skip the on-chain retry
        // sleeps — never leak the flag into other tests.
        Thread.interrupted();
    }

    /** Real (offline-built) Stellar transaction — submit() only passes it through. */
    private static Transaction buildDummyTransaction() {
        KeyPair buyer = KeyPair.random();
        Network network = new Network("Test SDF Network ; September 2015");
        Transaction tx = new TransactionBuilder(new Account(buyer.getAccountId(), 0L), network)
                .setBaseFee(100)
                .setTimeout(300)
                .addMemo(Memo.text("TOTAL: 10 XLM"))
                .addOperation(PaymentOperation.builder()
                        .destination(KeyPair.random().getAccountId())
                        .asset(Asset.createNativeAsset())
                        .amount(new BigDecimal("10.0000000"))
                        .build())
                .build();
        tx.sign(buyer);
        return tx;
    }

    private Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setTenantId(TENANT);
        o.setUserId(USER);
        o.setBuyerWallet("GBUYER");
        o.setMemo("TOTAL: 10 XLM");
        o.setAmountXlm(new BigDecimal("10"));
        o.setStellarTxHash(TX_HASH);
        o.setStatus(status);
        o.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(300));
        o.setTargetType(TargetType.ENTRY);
        o.setEntryId("entry1");
        return o;
    }

    /** Wires the full happy-path mock pipeline; individual tests override stages. */
    private void mockHappyPath() {
        when(orderRepository.tryLockForProcessing(eq(TENANT), eq(ORDER_ID), eq(USER), eq(SIGNED_XDR), any()))
                .thenReturn(Optional.of(order(OrderStatus.PROCESSING)));
        when(stellarTxService.verifySignedXdrAgainstOrder(eq(SIGNED_XDR), any(Order.class)))
                .thenReturn(parsedTx);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID))
                .thenReturn(false);
        when(stellarTxService.submitTransaction(parsedTx))
                .thenReturn(StellarTransactionService.SubmissionOutcome.SUBMITTED);
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class)))
                .thenReturn(true);
        when(orderRepository.tryComplete(eq(TENANT), eq(ORDER_ID), eq(TX_HASH), any()))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED)));
        when(orderRepository.expirePendingOrdersForUserExcept(TENANT, USER, ORDER_ID))
                .thenReturn(0L);
    }

    // ── Happy path ───────────────────────────────────────────────

    @Test
    void submit_happyPath_completesOrderAndCreatesEntitlement() {
        mockHappyPath();

        SubmitPaymentResponse response = service.submit(TENANT, USER, REQUEST);

        assertEquals(ORDER_ID, response.orderId());
        assertEquals(TX_HASH, response.stellarTxHash());
        assertEquals("COMPLETED", response.status());
        assertEquals("entry1", response.entryId());

        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        verify(entitlementRepository).save(captor.capture());
        Entitlement entitlement = captor.getValue();
        assertEquals(TENANT, entitlement.getTenantId());
        assertEquals(USER, entitlement.getUserId());
        assertEquals("entry1", entitlement.getEntryId());
        assertEquals(ORDER_ID, entitlement.getOrderId());

        verify(orderRepository).expirePendingOrdersForUserExcept(TENANT, USER, ORDER_ID);
    }

    @Test
    void submit_ambiguousHorizonOutcome_confirmedOnChain_completes() {
        mockHappyPath();
        when(stellarTxService.submitTransaction(parsedTx))
                .thenReturn(StellarTransactionService.SubmissionOutcome.UNKNOWN);

        SubmitPaymentResponse response = service.submit(TENANT, USER, REQUEST);

        assertEquals("COMPLETED", response.status());
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    // ── Lock failures (explainLockFailure) ───────────────────────

    @Test
    void submit_orderNotFound_throws() {
        when(orderRepository.tryLockForProcessing(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.submit(TENANT, USER, REQUEST));
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void submit_orderOwnedByAnotherUser_throws() {
        when(orderRepository.tryLockForProcessing(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        Order foreign = order(OrderStatus.PENDING);
        foreign.setUserId("someone-else");
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID)).thenReturn(Optional.of(foreign));

        assertThrows(IllegalArgumentException.class, () -> service.submit(TENANT, USER, REQUEST));
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void submit_expiredPendingOrder_isExpiredAndThrows() {
        when(orderRepository.tryLockForProcessing(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        Order expired = order(OrderStatus.PENDING);
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID)).thenReturn(Optional.of(expired));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.submit(TENANT, USER, REQUEST));
        assertEquals("Order has expired", e.getMessage());
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED);
    }

    @Test
    void submit_orderNotPending_throws() {
        when(orderRepository.tryLockForProcessing(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED)));

        assertThrows(IllegalStateException.class, () -> service.submit(TENANT, USER, REQUEST));
        verifyNoInteractions(entitlementRepository);
    }

    // ── XDR verification / anti-replay ───────────────────────────

    @Test
    void submit_tamperedXdr_failsOrderAndNeverSubmits() {
        mockHappyPath();
        when(stellarTxService.verifySignedXdrAgainstOrder(eq(SIGNED_XDR), any(Order.class)))
                .thenThrow(new SecurityException("hash mismatch"));

        assertThrows(IllegalArgumentException.class, () -> service.submit(TENANT, USER, REQUEST));

        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.FAILED);
        verify(stellarTxService, never()).submitTransaction(any());
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void submit_replayedTxHash_failsOrderAndNeverSubmits() {
        mockHappyPath();
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID))
                .thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.submit(TENANT, USER, REQUEST));

        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.FAILED);
        verify(stellarTxService, never()).submitTransaction(any());
        verifyNoInteractions(entitlementRepository);
    }

    // ── Horizon submission / on-chain confirmation ───────────────

    @Test
    void submit_rejectedByHorizon_failsOrder() {
        mockHappyPath();
        when(stellarTxService.submitTransaction(parsedTx))
                .thenReturn(StellarTransactionService.SubmissionOutcome.REJECTED);

        assertThrows(RuntimeException.class, () -> service.submit(TENANT, USER, REQUEST));

        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.FAILED);
        verify(orderRepository, never()).tryComplete(any(), any(), any(), any());
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void submit_neverConfirmedOnChain_failsOrder() {
        mockHappyPath();
        // Interrupt the submitting thread from inside the first verification so
        // the retry loop's sleep aborts instantly instead of waiting ~6 s.
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class)))
                .thenAnswer(inv -> {
                    Thread.currentThread().interrupt();
                    return false;
                });

        assertThrows(RuntimeException.class, () -> service.submit(TENANT, USER, REQUEST));

        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.FAILED);
        verify(orderRepository, never()).tryComplete(any(), any(), any(), any());
        verifyNoInteractions(entitlementRepository);
    }

    // ── Completion / entitlement ─────────────────────────────────

    @Test
    void submit_concurrentCompletion_throwsWithoutDuplicateEntitlement() {
        mockHappyPath();
        when(orderRepository.tryComplete(eq(TENANT), eq(ORDER_ID), eq(TX_HASH), any()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.submit(TENANT, USER, REQUEST));
        verifyNoInteractions(entitlementRepository);
    }

    @Test
    void submit_entitlementStoreFailure_stillReturnsSuccess() {
        // The money is on-chain: a transient entitlement-store failure must NOT
        // surface as a payment error — the reconciliation watchdog repairs it.
        mockHappyPath();
        when(entitlementRepository.save(any(Entitlement.class)))
                .thenThrow(new RuntimeException("Mongo down"));

        SubmitPaymentResponse response = service.submit(TENANT, USER, REQUEST);

        assertEquals("COMPLETED", response.status());
    }

    @Test
    void submit_duplicateEntitlement_isIdempotentSuccess() {
        mockHappyPath();
        when(entitlementRepository.save(any(Entitlement.class)))
                .thenThrow(new DuplicateKeyException("already exists"));

        SubmitPaymentResponse response = service.submit(TENANT, USER, REQUEST);

        assertEquals("COMPLETED", response.status());
    }
}
