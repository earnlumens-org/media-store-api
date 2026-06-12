package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentSubmissionCoordinator} — the async submit
 * orchestration (P0-4): inline lock+verify, 202/PROCESSING contract,
 * background finalization with tenant context, sync-mode rollback flag and
 * the inline fallback when the executor rejects work.
 */
class PaymentSubmissionCoordinatorTest {

    private static final String TENANT = "earnlumens";
    private static final String USER = "user1";
    private static final String ORDER_ID = "order1";
    private static final String TX_HASH = "aabbccdd00112233aabbccdd00112233aabbccdd00112233aabbccdd00112233";
    private static final SubmitPaymentRequest REQUEST = new SubmitPaymentRequest(ORDER_ID, "AAAA-signed-xdr");

    private PaymentService paymentService;
    private final List<Runnable> deferredTasks = new ArrayList<>();
    private final Executor deferringExecutor = deferredTasks::add;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        deferredTasks.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private Order processingOrder() {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setTenantId(TENANT);
        o.setUserId(USER);
        o.setStellarTxHash(TX_HASH);
        o.setStatus(OrderStatus.PROCESSING);
        o.setTargetType(TargetType.ENTRY);
        o.setEntryId("entry1");
        return o;
    }

    private PaymentService.LockedSubmission locked() {
        return new PaymentService.LockedSubmission(processingOrder(), null);
    }

    // ── Async mode ──────────────────────────────────────────────────────────

    @Test
    void asyncMode_returnsProcessingImmediately_withoutFinalizing() {
        PaymentService.LockedSubmission locked = locked();
        when(paymentService.lockAndVerify(TENANT, USER, REQUEST)).thenReturn(locked);
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, deferringExecutor, true);

        SubmitPaymentResponse response = coordinator.submit(TENANT, USER, REQUEST);

        assertEquals(OrderStatus.PROCESSING.name(), response.status());
        assertEquals(ORDER_ID, response.orderId());
        assertEquals(TX_HASH, response.stellarTxHash());
        assertEquals("entry1", response.entryId());
        assertNull(response.collectionId());
        // The blocking phase was scheduled but has NOT run on the caller thread.
        assertEquals(1, deferredTasks.size());
        verify(paymentService, never()).finalizeSubmission(any());
        verify(paymentService, never()).submit(any(), any(), any());
    }

    @Test
    void asyncMode_backgroundTaskFinalizes_withTenantContextSet() {
        PaymentService.LockedSubmission locked = locked();
        when(paymentService.lockAndVerify(TENANT, USER, REQUEST)).thenReturn(locked);
        AtomicReference<String> tenantSeen = new AtomicReference<>();
        when(paymentService.finalizeSubmission(locked)).thenAnswer(inv -> {
            tenantSeen.set(TenantContext.get());
            return null;
        });
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, deferringExecutor, true);

        coordinator.submit(TENANT, USER, REQUEST);
        deferredTasks.forEach(Runnable::run);

        verify(paymentService).finalizeSubmission(locked);
        assertEquals(TENANT, tenantSeen.get());
        // Context never leaks into the worker thread after the task.
        assertNull(TenantContext.get());
    }

    @Test
    void asyncMode_backgroundFailureIsSwallowed_orderLeftToWatchdog() {
        PaymentService.LockedSubmission locked = locked();
        when(paymentService.lockAndVerify(TENANT, USER, REQUEST)).thenReturn(locked);
        when(paymentService.finalizeSubmission(locked))
                .thenThrow(new RuntimeException("Horizon down"));
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, deferringExecutor, true);

        coordinator.submit(TENANT, USER, REQUEST);

        assertDoesNotThrow(() -> deferredTasks.forEach(Runnable::run));
        assertNull(TenantContext.get());
    }

    @Test
    void asyncMode_lockFailuresPropagateInline() {
        when(paymentService.lockAndVerify(TENANT, USER, REQUEST))
                .thenThrow(new IllegalStateException("Order has expired"));
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, deferringExecutor, true);

        assertThrows(IllegalStateException.class, () -> coordinator.submit(TENANT, USER, REQUEST));
        assertTrue(deferredTasks.isEmpty());
        verify(paymentService, never()).finalizeSubmission(any());
    }

    @Test
    void asyncMode_executorRejection_fallsBackToInlineFinalization() {
        PaymentService.LockedSubmission locked = locked();
        when(paymentService.lockAndVerify(TENANT, USER, REQUEST)).thenReturn(locked);
        SubmitPaymentResponse completed = new SubmitPaymentResponse(
                ORDER_ID, TX_HASH, OrderStatus.COMPLETED.name(), "entry1", null);
        when(paymentService.finalizeSubmission(locked)).thenReturn(completed);
        Executor rejecting = task -> { throw new RejectedExecutionException("saturated"); };
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, rejecting, true);

        SubmitPaymentResponse response = coordinator.submit(TENANT, USER, REQUEST);

        assertEquals(OrderStatus.COMPLETED.name(), response.status());
        verify(paymentService).finalizeSubmission(locked);
    }

    // ── Sync mode (rollback flag) ───────────────────────────────────────────

    @Test
    void syncMode_delegatesToLegacyBlockingPipeline() {
        SubmitPaymentResponse completed = new SubmitPaymentResponse(
                ORDER_ID, TX_HASH, OrderStatus.COMPLETED.name(), "entry1", null);
        when(paymentService.submit(TENANT, USER, REQUEST)).thenReturn(completed);
        PaymentSubmissionCoordinator coordinator =
                new PaymentSubmissionCoordinator(paymentService, deferringExecutor, false);

        SubmitPaymentResponse response = coordinator.submit(TENANT, USER, REQUEST);

        assertEquals(OrderStatus.COMPLETED.name(), response.status());
        assertTrue(deferredTasks.isEmpty());
        verify(paymentService).submit(TENANT, USER, REQUEST);
        verify(paymentService, never()).lockAndVerify(any(), any(), any());
    }
}
