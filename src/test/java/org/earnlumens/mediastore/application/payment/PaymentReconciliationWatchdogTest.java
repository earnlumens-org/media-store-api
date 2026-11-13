package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentReconciliationWatchdog}: scan delegation,
 * distributed-lock gating, missing-entitlement repair and error isolation.
 */
class PaymentReconciliationWatchdogTest {

    private OrderRepository orderRepository;
    private EntitlementRepository entitlementRepository;
    private PaymentService paymentService;
    private DistributedLockService lockService;
    private PaymentReconciliationWatchdog watchdog;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        paymentService = mock(PaymentService.class);
        lockService = mock(DistributedLockService.class);
        when(lockService.tryAcquire(anyString(), any())).thenReturn(true);
        watchdog = new PaymentReconciliationWatchdog(
                orderRepository, entitlementRepository, paymentService, lockService, 24);
    }

    private Order order(String id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setTenantId("earnlumens");
        o.setUserId("user1");
        o.setStatus(status);
        o.setStellarTxHash("hash-" + id);
        return o;
    }

    @Test
    void run_lockNotAcquired_doesNothing() {
        when(lockService.tryAcquire(anyString(), any())).thenReturn(false);

        watchdog.run();

        verifyNoInteractions(orderRepository, entitlementRepository, paymentService);
    }

    @Test
    void run_reconcilesStuckProcessingAndFailedOrders() {
        Order stuck = order("o1", OrderStatus.PROCESSING);
        Order failed = order("o2", OrderStatus.FAILED);
        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PROCESSING), any(), anyInt()))
                .thenReturn(List.of(stuck));
        when(orderRepository.findByStatus(eq(OrderStatus.FAILED), anyInt()))
                .thenReturn(List.of(failed));
        when(paymentService.reconcileOrder(any(Order.class)))
                .thenReturn(PaymentService.ReconcileOutcome.COMPLETED);

        watchdog.run();

        verify(paymentService).reconcileOrder(stuck);
        verify(paymentService).reconcileOrder(failed);
    }

    @Test
    void run_repairsCompletedOrdersMissingEntitlement() {
        Order withEntitlement = order("o1", OrderStatus.COMPLETED);
        Order missing = order("o2", OrderStatus.COMPLETED);
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(List.of(withEntitlement, missing));
        when(entitlementRepository.findOrderIdsWithEntitlements(any()))
                .thenReturn(Set.of("o1"));

        watchdog.run();

        verify(paymentService).ensureEntitlement(missing);
        verify(paymentService, never()).ensureEntitlement(withEntitlement);
    }

    @Test
    void run_reconcileFailure_doesNotAbortTheCycle() {
        Order broken = order("o1", OrderStatus.PROCESSING);
        Order recoverable = order("o2", OrderStatus.PROCESSING);
        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PROCESSING), any(), anyInt()))
                .thenReturn(List.of(broken, recoverable));
        when(paymentService.reconcileOrder(broken)).thenThrow(new RuntimeException("Horizon down"));
        when(paymentService.reconcileOrder(recoverable))
                .thenReturn(PaymentService.ReconcileOutcome.COMPLETED);

        // Must not throw, and must still process the second order.
        watchdog.run();

        verify(paymentService).reconcileOrder(recoverable);
    }

    @Test
    void run_entitlementRepairFailure_doesNotPropagate() {
        Order missing = order("o1", OrderStatus.COMPLETED);
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(List.of(missing));
        when(entitlementRepository.findOrderIdsWithEntitlements(any())).thenReturn(Set.of());
        doThrow(new RuntimeException("Mongo down")).when(paymentService).ensureEntitlement(missing);

        watchdog.run();

        verify(paymentService).ensureEntitlement(missing);
    }

    @Test
    void run_noWork_noRepairs() {
        watchdog.run();

        verify(paymentService, never()).reconcileOrder(any());
        verify(paymentService, never()).ensureEntitlement(any());
    }

    // ─── H5: backfill without a time window ─────────────────────

    private Order completed(String id, TargetType type, LocalDateTime completedAt) {
        Order o = order(id, OrderStatus.COMPLETED);
        o.setTargetType(type);
        o.setCompletedAt(completedAt);
        return o;
    }

    @Test
    void backfill_repairsA90DayOldOrder_scanStartsFromTheEpochNotFromALookback() {
        Order old = completed("o-old", TargetType.ENTRY, LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(List.of(old));
        when(entitlementRepository.findOrderIdsWithEntitlements(any())).thenReturn(Set.of());

        watchdog.run();

        ArgumentCaptor<LocalDateTime> after = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), after.capture(),
                eq(PaymentReconciliationWatchdog.BACKFILL_PAGE_SIZE));
        assertThat(after.getValue()).isBefore(LocalDateTime.of(1971, 1, 1, 0, 0));
        verify(paymentService).ensureEntitlement(old);
    }

    @Test
    void backfill_isIdempotent_secondRunIsANoOpOnceTheEntitlementExists() {
        Order old = completed("o-old", TargetType.ENTRY, LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(List.of(old));
        when(entitlementRepository.findOrderIdsWithEntitlements(any()))
                .thenReturn(Set.of())          // first cycle: missing
                .thenReturn(Set.of("o-old"));  // second cycle: repaired

        watchdog.run();
        watchdog.run();

        verify(paymentService, times(1)).ensureEntitlement(old);
    }

    @Test
    void backfill_ignoresTipsAndPublishingOrders() {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(List.of(
                        completed("tip", TargetType.TIP, t),
                        completed("fee", TargetType.PUBLISH_FEE, t),
                        completed("fast", TargetType.PUBLISH_FAST_PASS, t),
                        completed("coll", TargetType.COLLECTION, t)));
        when(entitlementRepository.findOrderIdsWithEntitlements(any())).thenReturn(Set.of());

        watchdog.run();

        verify(paymentService, times(1)).ensureEntitlement(any());
        verify(paymentService).ensureEntitlement(argThat(o -> "coll".equals(o.getId())));
    }

    @Test
    void backfill_pagesForwardWithTheCursor_thenWrapsToTheEpoch() {
        int page = PaymentReconciliationWatchdog.BACKFILL_PAGE_SIZE;
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Order> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < page; i++) fullPage.add(completed("o" + i, TargetType.ENTRY, t0.plusMinutes(i)));
        List<Order> lastPage = List.of(completed("o-last", TargetType.ENTRY, t0.plusDays(1)));
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(fullPage).thenReturn(lastPage).thenReturn(fullPage);
        when(entitlementRepository.findOrderIdsWithEntitlements(any())).thenAnswer(inv ->
                new java.util.HashSet<>((java.util.Collection<String>) inv.getArgument(0))); // all repaired already

        watchdog.run(); // page 1 → cursor = last completedAt of the page
        watchdog.run(); // page 2 (short) → cursor wraps
        watchdog.run(); // starts over from the epoch

        ArgumentCaptor<LocalDateTime> after = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository, times(3)).findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), after.capture(), anyInt());
        assertThat(after.getAllValues().get(0)).isBefore(t0);
        assertThat(after.getAllValues().get(1)).isEqualTo(t0.plusMinutes(page - 1));
        assertThat(after.getAllValues().get(2)).isBefore(t0);
        verify(paymentService, never()).ensureEntitlement(any());
    }

    @Test
    void backfillMissingEntitlements_onDemand_walksTheWholeHistory() {
        int page = PaymentReconciliationWatchdog.BACKFILL_PAGE_SIZE;
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<Order> fullPage = new java.util.ArrayList<>();
        for (int i = 0; i < page; i++) fullPage.add(completed("o" + i, TargetType.ENTRY, t0.plusMinutes(i)));
        Order missing = completed("o-missing", TargetType.ENTRY, t0.plusDays(1));
        when(orderRepository.findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt()))
                .thenReturn(fullPage).thenReturn(List.of(missing));
        when(entitlementRepository.findOrderIdsWithEntitlements(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().filter(id -> !id.equals("o-missing")).collect(java.util.stream.Collectors.toSet());
        });

        int repaired = watchdog.backfillMissingEntitlements();

        assertThat(repaired).isEqualTo(1);
        verify(paymentService).ensureEntitlement(missing);
        verify(orderRepository, times(2)).findByStatusAndCompletedAtAfter(eq(OrderStatus.COMPLETED), any(), anyInt());
    }
}
