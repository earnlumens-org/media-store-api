package org.earnlumens.mediastore.domain.publishing.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One entity waiting (or already released) in a Space's publishing queue.
 *
 * <p>Entity-agnostic: identifies its subject only through
 * ({@link #entityType}, {@link #entityId}). The same entity may hold one
 * active item per Space — queue, block, fee and FastPass are fully
 * independent between Spaces.
 *
 * <p><b>Ordering inside a block</b> (frozen at lock time):
 * <ol>
 *   <li>higher accumulated {@link #priorityFeeXlm} first;</li>
 *   <li>tie on the same fee → whoever REACHED that total first wins
 *       ({@link #feeLastIncreasedAt} ascending);</li>
 *   <li>no fee (0 XLM) → arrival order ({@link #enqueuedAt} ascending).</li>
 * </ol>
 *
 * <p>Fees are cumulative, any amount &gt; 0 XLM is valid, and they can be
 * increased until the block locks (1 minute before publication). Neither
 * the block assignment nor any confirmed payment can be reverted or
 * transferred.
 */
public class PublishingQueueItem {

    private String id;
    private String tenantId;
    private String spaceId;
    private String blockId;
    /** Denormalized from the block (immutable once assigned). */
    private long blockSequence;
    /** Denormalized from the block (immutable once assigned). */
    private LocalDateTime blockPublishAt;
    private PublishingEntityType entityType;
    private String entityId;
    /** OAuth user id of the owner who queued the entity. */
    private String userId;
    private PublishingQueueItemStatus status = PublishingQueueItemStatus.QUEUED;
    /** True when this item entered through a FastPass extra slot. */
    private boolean fastPass;
    /** Accumulated Publish Priority Fee in XLM (sum of confirmed payments). */
    private BigDecimal priorityFeeXlm = BigDecimal.ZERO;
    /** When the current fee total was reached (tie-break: earlier wins). */
    private LocalDateTime feeLastIncreasedAt;
    /** Final 1-based position inside the block, assigned when the block locks. */
    private Integer lockedPosition;
    /** Order ids whose payment effect was already applied (idempotency guard). */
    private List<String> appliedOrderIds = new ArrayList<>();
    private LocalDateTime enqueuedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime cancelledAt;

    public PublishingQueueItem() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }

    public long getBlockSequence() { return blockSequence; }
    public void setBlockSequence(long blockSequence) { this.blockSequence = blockSequence; }

    public LocalDateTime getBlockPublishAt() { return blockPublishAt; }
    public void setBlockPublishAt(LocalDateTime blockPublishAt) { this.blockPublishAt = blockPublishAt; }

    public PublishingEntityType getEntityType() { return entityType; }
    public void setEntityType(PublishingEntityType entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public PublishingQueueItemStatus getStatus() { return status; }
    public void setStatus(PublishingQueueItemStatus status) { this.status = status; }

    public boolean isFastPass() { return fastPass; }
    public void setFastPass(boolean fastPass) { this.fastPass = fastPass; }

    public BigDecimal getPriorityFeeXlm() { return priorityFeeXlm; }
    public void setPriorityFeeXlm(BigDecimal priorityFeeXlm) {
        this.priorityFeeXlm = priorityFeeXlm == null ? BigDecimal.ZERO : priorityFeeXlm;
    }

    public LocalDateTime getFeeLastIncreasedAt() { return feeLastIncreasedAt; }
    public void setFeeLastIncreasedAt(LocalDateTime feeLastIncreasedAt) { this.feeLastIncreasedAt = feeLastIncreasedAt; }

    public Integer getLockedPosition() { return lockedPosition; }
    public void setLockedPosition(Integer lockedPosition) { this.lockedPosition = lockedPosition; }

    public List<String> getAppliedOrderIds() { return appliedOrderIds; }
    public void setAppliedOrderIds(List<String> appliedOrderIds) {
        this.appliedOrderIds = appliedOrderIds == null ? new ArrayList<>() : appliedOrderIds;
    }

    public LocalDateTime getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(LocalDateTime enqueuedAt) { this.enqueuedAt = enqueuedAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    /** Active = still occupying a slot (waiting or frozen). */
    public boolean isActive() {
        return status == PublishingQueueItemStatus.QUEUED
                || status == PublishingQueueItemStatus.LOCKED;
    }
}
