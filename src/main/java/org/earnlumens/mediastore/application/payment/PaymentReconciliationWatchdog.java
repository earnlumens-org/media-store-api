package org.earnlumens.mediastore.application.payment;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;
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
 *       entitlement through the idempotent upsert (S-06). <b>No time window</b>
 *       (H5): every cycle pages through COMPLETED orders oldest-first with a
 *       {@code completedAt} cursor; when the end is reached the cursor wraps,
 *       so the whole history is re-verified continuously and from the first
 *       cycle after a restart. Each repair is logged at ERROR with the marker
 *       {@value #ALERT_MARKER} (alert), together with the age of the oldest
 *       unrepaired order seen (lag).</li>
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

    /** Page size of the entitlement backfill (no Horizon traffic: Mongo only). */
    static final int BACKFILL_PAGE_SIZE = BATCH_SIZE * 10;

    /** Stable log marker for alerting on repaired entitlements (H5 criterion 3). */
    public static final String ALERT_MARKER = "ENTITLEMENT_BACKFILL_REPAIRED";

    /** Cursor start: before any order ever completed. */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final OrderRepository orderRepository;
    private final EntitlementRepository entitlementRepository;
    private final PaymentService paymentService;
    private final DistributedLockService lockService;

    /**
     * Backfill cursor: {@code completedAt} of the last order examined. In
     * memory on purpose — a restart simply restarts the scan from the oldest
     * order, which is the desired "run on startup" behaviour.
     */
    private volatile LocalDateTime backfillCursor = EPOCH;

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
        // Retained for configuration compatibility; the backfill no longer has a window (H5).
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

    /**
     * One page of the window-less entitlement backfill (H5): COMPLETED orders
     * after the cursor, oldest first. Idempotent (the upsert of S-06) and safe
     * with several instances (each repair is a conditional write guarded by
     * the H9 unique indexes). Runs on demand too — see {@link #backfillMissingEntitlements()}.
     */
    private int repairMissingEntitlements() {
        return backfillPage();
    }

    /**
     * Runs the backfill over the <b>entire</b> history right now (operator /
     * startup use), page after page until the scan wraps.
     *
     * @return entitlements repaired
     */
    public int backfillMissingEntitlements() {
        backfillCursor = EPOCH;
        int repaired = 0;
        do {
            repaired += backfillPage();
        } while (!backfillCursor.equals(EPOCH));
        return repaired;
    }

    private int backfillPage() {
        LocalDateTime after = backfillCursor;
        List<Order> completed = orderRepository.findByStatusAndCompletedAtAfter(
                OrderStatus.COMPLETED, after, BACKFILL_PAGE_SIZE);
        if (completed.isEmpty()) {
            backfillCursor = EPOCH;
            return 0;
        }
        // Advance (or wrap when the history is exhausted) before repairing so a
        // throwing page never stalls the cursor. A page that cannot advance
        // (all orders share one completedAt) wraps as well rather than loop.
        LocalDateTime last = completed.get(completed.size() - 1).getCompletedAt();
        boolean exhausted = completed.size() < BACKFILL_PAGE_SIZE || last == null || !last.isAfter(after);
        backfillCursor = exhausted ? EPOCH : last;

        Set<String> withEntitlement = entitlementRepository.findOrderIdsWithEntitlements(
                completed.stream().map(Order::getId).toList());
        int repaired = 0;
        LocalDateTime oldestMissing = null;
        for (Order order : completed) {
            if (withEntitlement.contains(order.getId())) {
                continue;
            }
            // Tips and publishing fees never have an entitlement by design — skip
            // them so they are not reported (and "repaired") on every cycle.
            TargetType t = order.getTargetType();
            if (t == TargetType.TIP || t == TargetType.PUBLISH_FEE || t == TargetType.PUBLISH_FAST_PASS) {
                continue;
            }
            if (oldestMissing == null && order.getCompletedAt() != null) {
                oldestMissing = order.getCompletedAt();
            }
            try {
                paymentService.ensureEntitlement(order);
                repaired++;
                logger.error("{} orderId={} userId={} targetType={} entryId={} collectionId={} completedAt={} txHash={}",
                        ALERT_MARKER, order.getId(), order.getUserId(), t, order.getEntryId(),
                        order.getCollectionId(), order.getCompletedAt(), order.getStellarTxHash());
            } catch (Exception e) {
                logger.error("Failed to repair missing entitlement for order {}: {}",
                        order.getId(), e.getMessage(), e);
            }
        }
        if (repaired > 0) {
            long lagSeconds = oldestMissing == null ? -1
                    : Duration.between(oldestMissing, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
            logger.error("{} summary repaired={} page={} oldestMissingCompletedAt={} lagSeconds={}",
                    ALERT_MARKER, repaired, completed.size(), oldestMissing, lagSeconds);
        }
        return repaired;
    }
}
