package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
}
