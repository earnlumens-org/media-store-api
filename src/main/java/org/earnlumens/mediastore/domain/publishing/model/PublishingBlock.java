package org.earnlumens.mediastore.domain.publishing.model;

import java.time.LocalDateTime;

/**
 * A Publishing Block: a fixed-capacity batch of queue items that a Space
 * releases to its feed at {@link #publishAt}.
 *
 * <p><b>Mechanics</b>
 * <ul>
 *   <li>Every Space generates its own independent sequence of blocks
 *       ({@code sequence} is 1-based per (tenantId, spaceId)).</li>
 *   <li>Blocks are created lazily when a queue item needs a slot. A new
 *       block publishes at {@code max(previous.publishAt, now) + interval}
 *       (interval configurable per Space, default 10 minutes).</li>
 *   <li>{@code baseCapacity} base slots (default 48). Once they are all
 *       taken, FastPass purchases may add extra slots ({@code fastPassSlots},
 *       unlimited) to THIS block only — other blocks are unaffected.</li>
 *   <li>At {@code lockAt} (1 minute before {@code publishAt}) the block
 *       LOCKS: the internal order is frozen and items can no longer be
 *       modified or cancelled.</li>
 *   <li>Block assignment is final: an item never moves to another block.
 *       A cancelled item frees its base slot for future entrants of the
 *       same block without shifting anyone else.</li>
 * </ul>
 */
public class PublishingBlock {

    private String id;
    private String tenantId;
    private String spaceId;
    /** 1-based, monotonically increasing per (tenantId, spaceId). */
    private long sequence;
    private PublishingBlockStatus status = PublishingBlockStatus.OPEN;
    /** Number of base slots (snapshot of the Space config at creation). */
    private int baseCapacity;
    /** Base slots currently reserved (atomically maintained; never above baseCapacity). */
    private int baseSlotsUsed;
    /** Extra slots added by confirmed FastPass purchases. Unlimited. */
    private int fastPassSlots;
    /** When the block order freezes (UTC). Always {@code publishAt} minus the lock lead. */
    private LocalDateTime lockAt;
    /** When the block content is released to the space feed (UTC). */
    private LocalDateTime publishAt;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public PublishingBlock() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public PublishingBlockStatus getStatus() { return status; }
    public void setStatus(PublishingBlockStatus status) { this.status = status; }

    public int getBaseCapacity() { return baseCapacity; }
    public void setBaseCapacity(int baseCapacity) { this.baseCapacity = baseCapacity; }

    public int getBaseSlotsUsed() { return baseSlotsUsed; }
    public void setBaseSlotsUsed(int baseSlotsUsed) { this.baseSlotsUsed = baseSlotsUsed; }

    public int getFastPassSlots() { return fastPassSlots; }
    public void setFastPassSlots(int fastPassSlots) { this.fastPassSlots = fastPassSlots; }

    public LocalDateTime getLockAt() { return lockAt; }
    public void setLockAt(LocalDateTime lockAt) { this.lockAt = lockAt; }

    public LocalDateTime getPublishAt() { return publishAt; }
    public void setPublishAt(LocalDateTime publishAt) { this.publishAt = publishAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    /** True while the block still accepts new base-slot entrants. */
    public boolean hasFreeBaseSlot() {
        return status == PublishingBlockStatus.OPEN && baseSlotsUsed < baseCapacity;
    }
}
