package org.earnlumens.mediastore.domain.publishing.repository;

import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItem;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItemStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for publishing queue items. Fee accumulation and status
 * transitions are atomic so concurrent payment confirmations, cancels and
 * scheduler locks can never corrupt an item.
 */
public interface PublishingQueueItemRepository {

    PublishingQueueItem save(PublishingQueueItem item);

    Optional<PublishingQueueItem> findByTenantIdAndId(String tenantId, String id);

    /** Active (QUEUED/LOCKED) item of an entity in ONE space, if any. */
    Optional<PublishingQueueItem> findActiveByEntityAndSpace(String tenantId,
                                                             PublishingEntityType entityType,
                                                             String entityId,
                                                             String spaceId);

    /** Every item (any status) of an entity across all spaces, newest first. */
    List<PublishingQueueItem> findByEntity(String tenantId,
                                           PublishingEntityType entityType,
                                           String entityId);

    /** Items of a block filtered by status. */
    List<PublishingQueueItem> findByBlockIdAndStatus(String tenantId, String blockId,
                                                     PublishingQueueItemStatus status);

    /** Items of a block in a set of statuses. */
    List<PublishingQueueItem> findByBlockIdAndStatusIn(String tenantId, String blockId,
                                                       List<PublishingQueueItemStatus> statuses);

    /** Number of active items in the space assigned to blocks BEFORE the given sequence. */
    long countActiveBySpaceBeforeSequence(String tenantId, String spaceId, long sequence);

    /** Number of active items in the whole space queue. */
    long countActiveBySpace(String tenantId, String spaceId);

    /**
     * Atomic CAS: QUEUED → CANCELLED, only when the item belongs to
     * {@code userId}. Returns the previous item state, empty when the CAS lost.
     */
    Optional<PublishingQueueItem> tryCancel(String tenantId, String itemId, String userId,
                                            LocalDateTime now);

    /** Atomic CAS: QUEUED → LOCKED with the frozen 1-based position. */
    Optional<PublishingQueueItem> tryLock(String tenantId, String itemId, int position);

    /** Atomic CAS: LOCKED → PUBLISHED. */
    Optional<PublishingQueueItem> tryMarkPublished(String tenantId, String itemId,
                                                   LocalDateTime publishedAt);

    /** Atomic CAS: LOCKED → CANCELLED (entity was no longer publishable at release time). */
    Optional<PublishingQueueItem> tryMarkDiscarded(String tenantId, String itemId,
                                                   LocalDateTime now);

    /**
     * Atomically applies a confirmed priority-fee payment: pushes
     * {@code orderId} into {@code appliedOrderIds} (only when absent — the
     * idempotency guard), increments {@code priorityFeeXlm} by
     * {@code amountXlm} and stamps {@code feeLastIncreasedAt}.
     *
     * @return the updated item, or empty when the order was already applied
     *         or the item does not exist.
     */
    Optional<PublishingQueueItem> tryApplyFeePayment(String tenantId, String itemId,
                                                     String orderId, BigDecimal amountXlm,
                                                     LocalDateTime now);
}
