package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.OrderRepository;
import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * Periodic reconciliation watchdog enforcing the core payment invariant:
 * <b>a payment confirmed on-chain ALWAYS ends with the content released</b>
 * (COMPLETED order + ACTIVE entitlement), no matter where the server crashed
 * or which network call failed during {@code submit()}.
 *
 * <p>Repairs three failure modes, all against Horizon as the only source of truth:
 * <ol>
 *   <li><b>Stuck PROCESSING</b> — server died between Horizon submission and
 *       completion. Once the tx window (timebounds) + grace has elapsed the
 *       verdict is deterministic: confirmed on-chain → complete + entitle;
 *       never landed → finalize as EXPIRED so the buyer can retry.</li>
 *   <li><b>Premature FAILED</b> — the tx landed in a ledger after the submit
 *       polling window gave up. Re-checked every cycle while the timebounds
 *       are open and once after they close: landed → promote to COMPLETED +
 *       entitlement; never landed → recycle to EXPIRED.</li>
 *   <li><b>COMPLETED without entitlement</b> — server died (or Mongo failed)
 *       between completion and entitlement creation. Recreates the missing
 *       entitlement (idempotent: unique index treats duplicates as success).</li>
 * </ol>
 *
 * <p>All state transitions inside {@link PaymentService#reconcileOrder(Order)}
 * are CAS operations, so this watchdog is safe to run on multiple instances
 * (the distributed lock merely avoids redundant Horizon traffic) and can never
 * race a live submit into a double entitlement or a downgraded COMPLETED order.
 */
@Component
public class PaymentReconciliationWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReconciliationWatchdog.class);

    /** Max orders examined per scan per cycle (keeps Horizon traffic bounded). */
    private static final int BATCH_SIZE = 50;

    private final OrderRepository orderRepository;
    private final EntitlementRepository entitlementRepository;
    private final PaymentService paymentService;
    private final DistributedLockService lockService;

    /** How far back to look for COMPLETED orders missing their entitlement. */
    private final Duration completedLookback;

    public PaymentReconciliationWatchdog(OrderRepository orderRepository,
                                         EntitlementRepository entitlementRepository,
                                         PaymentService paymentService,
                                         DistributedLockService lockService,
                                         @Value("${mediastore.payments.reconcile-completed-lookback-hours:24}")
                                         long completedLookbackHours) {
        this.orderRepository = orderRepository;
        this.entitlementRepository = entitlementRepository;
        this.paymentService = paymentService;
        this.lockService = lockService;
        this.completedLookback = Duration.ofHours(completedLookbackHours);
    }

    @Scheduled(fixedDelayString = "${mediastore.payments.watchdog-interval-ms:30000}",
               initialDelayString = "${mediastore.payments.watchdog-interval-ms:30000}")
    public void run() {
        if (!lockService.tryAcquire("payment-reconciliation-watchdog", Duration.ofSeconds(25))) {
            return; // another instance is running this cycle
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                int recovered = reconcileStuckProcessing();
                recovered += reconcileFailed();
                recovered += repairMissingEntitlements();
                if (recovered > 0) {
                    logger.info("Payment reconciliation cycle complete: repaired {} order(s)", recovered);
                }
            } catch (Exception e) {
                logger.error("Payment reconciliation cycle failed: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * PROCESSING orders whose tx window + grace elapsed: no submit thread can
     * still be working on them, and the timebounds make the on-chain verdict final.
     */
    private int reconcileStuckProcessing() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusSeconds(PaymentService.RECONCILE_GRACE_SECONDS);
        List<Order> stuck = orderRepository.findByStatusAndExpiresAtBefore(
                OrderStatus.PROCESSING, cutoff, BATCH_SIZE);
        int recovered = 0;
        for (Order order : stuck) {
            if (reconcileQuietly(order)) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * FAILED orders: re-checked while their timebounds are open (a tx can land
     * after the submit polling gave up) and finalized to EXPIRED afterwards, so
     * each FAILED order leaves this scan after at most one post-window check.
     */
    private int reconcileFailed() {
        List<Order> failed = orderRepository.findByStatus(OrderStatus.FAILED, BATCH_SIZE);
        int recovered = 0;
        for (Order order : failed) {
            if (reconcileQuietly(order)) {
                recovered++;
            }
        }
        return recovered;
    }

    private boolean reconcileQuietly(Order order) {
        try {
            return paymentService.reconcileOrder(order) == PaymentService.ReconcileOutcome.COMPLETED;
        } catch (Exception e) {
            logger.error("Failed to reconcile order {} (status={}): {}",
                    order.getId(), order.getStatus(), e.getMessage(), e);
            return false;
        }
    }

    /** Recreates entitlements for recently COMPLETED orders that lost theirs. */
    private int repairMissingEntitlements() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(completedLookback);
        List<Order> completed = orderRepository.findByStatusAndCompletedAtAfter(
                OrderStatus.COMPLETED, cutoff, BATCH_SIZE * 10);
        if (completed.isEmpty()) {
            return 0;
        }
        Set<String> withEntitlement = entitlementRepository.findOrderIdsWithEntitlements(
                completed.stream().map(Order::getId).toList());
        int repaired = 0;
        for (Order order : completed) {
            if (withEntitlement.contains(order.getId())) {
                continue;
            }
            try {
                logger.warn("COMPLETED order without entitlement detected — repairing: orderId={}, "
                        + "userId={}, txHash={}", order.getId(), order.getUserId(), order.getStellarTxHash());
                paymentService.ensureEntitlement(order);
                repaired++;
            } catch (Exception e) {
                logger.error("Failed to repair missing entitlement for order {}: {}",
                        order.getId(), e.getMessage(), e);
            }
        }
        return repaired;
    }
}
