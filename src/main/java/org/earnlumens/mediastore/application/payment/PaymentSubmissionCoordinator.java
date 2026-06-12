package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.dto.request.SubmitPaymentRequest;
import org.earnlumens.mediastore.domain.media.dto.response.SubmitPaymentResponse;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Orchestrates the submit phase of the payment flow without blocking request
 * threads (scalability P0-4).
 *
 * <p><b>Async mode (default):</b> the fast, purely-local pipeline
 * ({@link PaymentService#lockAndVerify} — atomic PROCESSING lock, signed-XDR
 * verification, anti-replay) runs inline and any rejection still surfaces as
 * an immediate 4xx. The blocking part ({@link PaymentService#finalizeSubmission}
 * — Horizon submission + up to ~20 s of on-chain confirmation polling) is
 * handed to a dedicated executor and the caller gets the order back in
 * {@code PROCESSING} state right away (HTTP 202). The frontend polls
 * {@code GET /api/payments/orders/{orderId}} until the order reaches a final
 * state.
 *
 * <p><b>Safety net:</b> nothing changes in the correctness model. Every state
 * transition stays CAS-based, and the {@link PaymentReconciliationWatchdog}
 * already repairs any order whose background finalization dies mid-flight
 * (instance shutdown, Horizon outage) — Horizon remains the only source of
 * truth for the final verdict.
 *
 * <p><b>Sync mode</b> ({@code mediastore.payments.async-submit=false}): the
 * legacy single-request pipeline, kept as a rollback flag during the
 * transition.
 */
@Service
public class PaymentSubmissionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(PaymentSubmissionCoordinator.class);

    private final PaymentService paymentService;
    private final Executor paymentSubmitExecutor;
    private final boolean asyncSubmitEnabled;

    public PaymentSubmissionCoordinator(PaymentService paymentService,
                                        @Qualifier("paymentSubmitExecutor") Executor paymentSubmitExecutor,
                                        @Value("${mediastore.payments.async-submit:true}") boolean asyncSubmitEnabled) {
        this.paymentService = paymentService;
        this.paymentSubmitExecutor = paymentSubmitExecutor;
        this.asyncSubmitEnabled = asyncSubmitEnabled;
    }

    /**
     * Submits a signed transaction. Returns either a final {@code COMPLETED}
     * response (sync mode / inline fallback) or a {@code PROCESSING} response
     * whose outcome must be polled (async mode).
     */
    public SubmitPaymentResponse submit(String tenantId, String userId, SubmitPaymentRequest request) {
        if (!asyncSubmitEnabled) {
            return paymentService.submit(tenantId, userId, request);
        }

        PaymentService.LockedSubmission locked = paymentService.lockAndVerify(tenantId, userId, request);
        Order order = locked.order();

        try {
            paymentSubmitExecutor.execute(() -> finalizeInBackground(tenantId, locked));
        } catch (RejectedExecutionException e) {
            // Executor saturated or shutting down — finalize inline rather than
            // leaving the buyer to the watchdog's slower reconciliation path.
            logger.warn("Payment submit executor rejected task — finalizing inline: orderId={}", order.getId());
            return paymentService.finalizeSubmission(locked);
        }

        return new SubmitPaymentResponse(
                order.getId(),
                order.getStellarTxHash(),
                OrderStatus.PROCESSING.name(),
                order.getEntryId(),
                order.getCollectionId()
        );
    }

    private void finalizeInBackground(String tenantId, PaymentService.LockedSubmission locked) {
        TenantContext.set(tenantId);
        try {
            paymentService.finalizeSubmission(locked);
        } catch (Exception e) {
            // The pipeline already moved the order to FAILED on every error path;
            // the reconciliation watchdog issues the final on-chain verdict.
            logger.error("Async payment finalization failed (order left to reconciliation): orderId={}: {}",
                    locked.order().getId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
