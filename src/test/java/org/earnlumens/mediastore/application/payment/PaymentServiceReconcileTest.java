package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.application.payment.PaymentService.ReconcileOutcome;
import org.earnlumens.mediastore.domain.media.model.Entitlement;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.config.StellarConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the payment self-healing logic:
 * <ul>
 *   <li>{@link PaymentService#reconcileOrder(Order)} — Horizon-as-truth recovery
 *       of PROCESSING/FAILED orders (crash between submission and completion,
 *       or a tx that landed after the polling window gave up);</li>
 *   <li>{@code processExistingOrders} — the prepare-time state machine that
 *       reuses, blocks, self-heals or expires previous orders for the same
 *       buyer + target (double-payment guard).</li>
 * </ul>
 */
class PaymentServiceReconcileTest {

    private static final String TENANT = "earnlumens";
    private static final String USER = "user1";
    private static final String ORDER_ID = "order1";
    private static final String TX_HASH = "ffeeddcc00112233ffeeddcc00112233ffeeddcc00112233ffeeddcc00112233";

    private OrderRepository orderRepository;
    private EntitlementRepository entitlementRepository;
    private StellarTransactionService stellarTxService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        stellarTxService = mock(StellarTransactionService.class);
        service = new PaymentService(
                null, null, orderRepository, entitlementRepository,
                stellarTxService, new StellarConfig(), null, null, null, null);
    }

    private Order order(OrderStatus status, LocalDateTime expiresAt) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setTenantId(TENANT);
        o.setUserId(USER);
        o.setBuyerWallet("GBUYER");
        o.setAmountXlm(new BigDecimal("10"));
        o.setStellarTxHash(TX_HASH);
        o.setStatus(status);
        o.setExpiresAt(expiresAt);
        o.setTargetType(TargetType.ENTRY);
        o.setEntryId("entry1");
        return o;
    }

    private LocalDateTime openWindow() {
        return LocalDateTime.now(ZoneOffset.UTC).plusSeconds(200);
    }

    private LocalDateTime closedWindow() {
        // Beyond expiresAt + RECONCILE_GRACE_SECONDS — verdict is final.
        return LocalDateTime.now(ZoneOffset.UTC)
                .minusSeconds(PaymentService.RECONCILE_GRACE_SECONDS + 60);
    }

    // ── reconcileOrder ───────────────────────────────────────────

    @Test
    void reconcile_rejectsNonReconcilableStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileOrder(order(OrderStatus.PENDING, openWindow())));
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileOrder(order(OrderStatus.COMPLETED, openWindow())));
    }

    @Test
    void reconcile_confirmedOnChain_completesAndCreatesEntitlement() {
        Order stuck = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(eq(TENANT), eq(ORDER_ID), eq(OrderStatus.PROCESSING),
                eq(TX_HASH), any()))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, closedWindow())));

        ReconcileOutcome outcome = service.reconcileOrder(stuck);

        assertEquals(ReconcileOutcome.COMPLETED, outcome);
        verify(entitlementRepository).save(any(Entitlement.class));
        verify(orderRepository).expirePendingOrdersForUserExcept(TENANT, USER, ORDER_ID);
    }

    @Test
    void reconcile_prematureFailed_landedLater_isPromotedToCompleted() {
        Order failed = order(OrderStatus.FAILED, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(eq(TENANT), eq(ORDER_ID), eq(OrderStatus.FAILED),
                eq(TX_HASH), any()))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, openWindow())));

        assertEquals(ReconcileOutcome.COMPLETED, service.reconcileOrder(failed));
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void reconcile_confirmedButReplayed_finalizesAsExpired() {
        Order stuck = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(true);

        ReconcileOutcome outcome = service.reconcileOrder(stuck);

        assertEquals(ReconcileOutcome.FINALIZED_NOT_ON_CHAIN, outcome);
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.EXPIRED);
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void reconcile_casLost_orderNowCompleted_ensuresEntitlement() {
        Order stuck = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, closedWindow())));

        assertEquals(ReconcileOutcome.COMPLETED, service.reconcileOrder(stuck));
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void reconcile_casLost_orderNotCompleted_staysInFlight() {
        Order stuck = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(orderRepository.findByTenantIdAndId(TENANT, ORDER_ID))
                .thenReturn(Optional.of(order(OrderStatus.PROCESSING, closedWindow())));

        assertEquals(ReconcileOutcome.IN_FLIGHT, service.reconcileOrder(stuck));
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void reconcile_notOnChain_windowStillOpen_noVerdict() {
        Order stuck = order(OrderStatus.PROCESSING, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        assertEquals(ReconcileOutcome.IN_FLIGHT, service.reconcileOrder(stuck));
        verify(orderRepository, never()).tryTransitionStatus(any(), any(), any(), any());
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void reconcile_notOnChain_windowClosed_finalizesAsExpired() {
        Order stuck = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        assertEquals(ReconcileOutcome.FINALIZED_NOT_ON_CHAIN, service.reconcileOrder(stuck));
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.EXPIRED);
    }

    @Test
    void reconcile_notOnChain_nullExpiry_finalizesAsExpired() {
        Order stuck = order(OrderStatus.PROCESSING, null);
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        assertEquals(ReconcileOutcome.FINALIZED_NOT_ON_CHAIN, service.reconcileOrder(stuck));
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.EXPIRED);
    }

    // ── processExistingOrders (prepare-time state machine) ───────

    private Order invokeProcess(Order existing) {
        return ReflectionTestUtils.invokeMethod(service, "processExistingOrders", List.of(existing));
    }

    @Test
    void existing_completedWithEntitlement_rejectsAsAlreadyPurchased() {
        Order completed = order(OrderStatus.COMPLETED, closedWindow());
        when(entitlementRepository.findOrderIdsWithEntitlements(List.of(ORDER_ID)))
                .thenReturn(Set.of(ORDER_ID));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> invokeProcess(completed));
        assertEquals("Content already purchased", e.getMessage());
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    void existing_completedWithoutEntitlement_selfHealsThenRejects() {
        Order completed = order(OrderStatus.COMPLETED, closedWindow());
        when(entitlementRepository.findOrderIdsWithEntitlements(List.of(ORDER_ID)))
                .thenReturn(Set.of());

        assertThrows(IllegalStateException.class, () -> invokeProcess(completed));
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void existing_freshPending_isReused() {
        Order pending = order(OrderStatus.PENDING, openWindow());

        assertSame(pending, invokeProcess(pending));
        verify(orderRepository, never()).tryTransitionStatus(any(), any(), any(), any());
    }

    @Test
    void existing_stalePending_isExpired() {
        Order stale = order(OrderStatus.PENDING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        assertNull(invokeProcess(stale));
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PENDING, OrderStatus.EXPIRED);
    }

    @Test
    void existing_processingInFlight_blocksWithPaymentInProgress() {
        // Not on-chain yet but the tx window is open: a submit may still be
        // running — creating a new order here is the double-payment path.
        Order processing = order(OrderStatus.PROCESSING, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> invokeProcess(processing));
        assertEquals("PAYMENT_IN_PROGRESS", e.getMessage());
    }

    @Test
    void existing_processingConfirmedOnChain_rejectsAsAlreadyPurchased() {
        Order processing = order(OrderStatus.PROCESSING, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(eq(TENANT), eq(ORDER_ID), eq(OrderStatus.PROCESSING),
                eq(TX_HASH), any()))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, openWindow())));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> invokeProcess(processing));
        assertEquals("Content already purchased", e.getMessage());
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void existing_processingWindowClosedNotOnChain_allowsNewPurchase() {
        Order processing = order(OrderStatus.PROCESSING, closedWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        assertNull(invokeProcess(processing));
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.PROCESSING, OrderStatus.EXPIRED);
    }

    @Test
    void existing_failedNotOnChain_isRecycledForHonestRetry() {
        Order failed = order(OrderStatus.FAILED, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(false);

        assertNull(invokeProcess(failed));
        verify(orderRepository).tryTransitionStatus(TENANT, ORDER_ID, OrderStatus.FAILED, OrderStatus.EXPIRED);
    }

    @Test
    void existing_failedButLandedOnChain_rejectsAsAlreadyPurchased() {
        Order failed = order(OrderStatus.FAILED, openWindow());
        when(stellarTxService.verifyTransactionOnChain(eq(TX_HASH), any(Order.class))).thenReturn(true);
        when(orderRepository.existsCompletedByStellarTxHashExcludingOrder(TX_HASH, ORDER_ID)).thenReturn(false);
        when(orderRepository.tryCompleteFrom(eq(TENANT), eq(ORDER_ID), eq(OrderStatus.FAILED),
                eq(TX_HASH), any()))
                .thenReturn(Optional.of(order(OrderStatus.COMPLETED, openWindow())));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> invokeProcess(failed));
        assertEquals("Content already purchased", e.getMessage());
        verify(entitlementRepository).save(any(Entitlement.class));
    }

    @Test
    void existing_expiredOrder_isIgnored() {
        assertNull(invokeProcess(order(OrderStatus.EXPIRED, closedWindow())));
        verifyNoInteractions(stellarTxService);
    }
}
