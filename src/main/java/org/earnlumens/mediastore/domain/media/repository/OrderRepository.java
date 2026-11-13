package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.OrderStatus;
import org.earnlumens.mediastore.domain.media.model.TargetType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    /**
     * Existing orders of a buyer for one entry, restricted to {@code targetType}
     * (duplicate purchase check). The filter matters: {@code TIP} orders carry
     * the tipped entry's id as context and must never be mistaken for purchases.
     */
    List<Order> findAllByTenantIdAndUserIdAndTargetTypeAndEntryId(
            String tenantId, String userId, TargetType targetType, String entryId);

    /** Existing orders of a buyer for one collection, restricted to {@code targetType} (duplicate purchase check). */
    List<Order> findAllByTenantIdAndUserIdAndTargetTypeAndCollectionId(
            String tenantId, String userId, TargetType targetType, String collectionId);

    Optional<Order> findByTenantIdAndId(String tenantId, String id);

    /** Find expired PENDING orders for cleanup within a tenant */
    List<Order> findByTenantIdAndStatusAndExpiresAtBefore(String tenantId, OrderStatus status, LocalDateTime cutoff);

    /** Find all orders for a buyer in a given status (used to expire stale PENDING orders after sequence change) */
    List<Order> findAllByTenantIdAndUserIdAndStatus(String tenantId, String userId, OrderStatus status);

    /** Count completed sales for a seller within a tenant */
    long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status);

    /** List completed sales for a seller within a tenant, newest first */
    List<Order> findByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, OrderStatus status);

    Order save(Order order);

    // ── Atomic state-machine operations (race-condition safe) ──

    /**
     * Atomically locks an order for submission: PENDING → PROCESSING.
     * The compare-and-swap query enforces, in a single atomic operation:
     * tenant match, ownership, PENDING status and non-expired window.
     * Stores the signed XDR as part of the same update.
     *
     * @return the locked order, or empty if any precondition failed (lock not acquired)
     */
    Optional<Order> tryLockForProcessing(String tenantId, String orderId, String userId,
                                         String signedXdr, LocalDateTime now);

    /**
     * Atomically completes an order: PROCESSING → COMPLETED, setting the
     * verified Stellar tx hash and completion timestamp.
     *
     * @return the completed order, or empty if the order was no longer PROCESSING
     */
    Optional<Order> tryComplete(String tenantId, String orderId, String stellarTxHash,
                                LocalDateTime completedAt);

    /**
     * Atomically completes an order from an arbitrary expected status
     * (PROCESSING or FAILED) — used by payment reconciliation when an order's
     * tx is found confirmed on-chain after a crash or a premature failure.
     *
     * @return the completed order, or empty if the current status did not match {@code expected}
     */
    Optional<Order> tryCompleteFrom(String tenantId, String orderId, OrderStatus expected,
                                    String stellarTxHash, LocalDateTime completedAt);

    /**
     * Atomic compare-and-swap of the order status.
     *
     * @return the updated order, or empty if the current status did not match {@code expected}
     */
    Optional<Order> tryTransitionStatus(String tenantId, String orderId,
                                        OrderStatus expected, OrderStatus next);

    /**
     * Anti-replay: true if any other order is already COMPLETED with this Stellar tx hash.
     * Cross-tenant on purpose — an on-chain tx hash can only ever unlock content once.
     */
    boolean existsCompletedByStellarTxHashExcludingOrder(String stellarTxHash, String excludeOrderId);

    /**
     * Atomically expires every PENDING order of a buyer except the given one
     * (single bulk update — no read-modify-write races).
     *
     * @return number of orders expired
     */
    long expirePendingOrdersForUserExcept(String tenantId, String userId, String excludeOrderId);

    // ── Payment reconciliation watchdog scans (deliberately cross-tenant) ──

    /** Orders in {@code status} whose tx window closed before {@code cutoff} (stale PROCESSING). */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, LocalDateTime cutoff, int limit);

    /** Orders in {@code status} (FAILED re-check scan). */
    List<Order> findByStatus(OrderStatus status, int limit);

    /**
     * Orders in {@code status} completed strictly after {@code cutoff},
     * <b>oldest first</b>, at most {@code limit} — the page of the
     * entitlement backfill cursor (H5).
     */
    List<Order> findByStatusAndCompletedAtAfter(OrderStatus status, LocalDateTime cutoff, int limit);
}
