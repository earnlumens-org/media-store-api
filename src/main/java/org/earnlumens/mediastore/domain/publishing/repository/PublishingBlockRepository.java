package org.earnlumens.mediastore.domain.publishing.repository;

import org.earnlumens.mediastore.domain.publishing.model.PublishingBlock;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlockStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for publishing blocks. All slot mutations are atomic
 * (findAndModify) so concurrent enqueues can never oversell a block.
 * Tenant-scoped everywhere except the scheduler sweeps (platform-level).
 */
public interface PublishingBlockRepository {

    PublishingBlock save(PublishingBlock block);

    Optional<PublishingBlock> findByTenantIdAndId(String tenantId, String id);

    List<PublishingBlock> findByTenantIdAndIdIn(String tenantId, List<String> ids);

    /** Earliest OPEN block of the space (lowest sequence), regardless of capacity. */
    Optional<PublishingBlock> findEarliestOpenBlock(String tenantId, String spaceId);

    /** Earliest OPEN block of the space that still has a free base slot. */
    Optional<PublishingBlock> findEarliestOpenBlockWithFreeBaseSlot(String tenantId, String spaceId);

    /** Highest-sequence block of the space in ANY status (to compute the next sequence). */
    Optional<PublishingBlock> findLatestBlock(String tenantId, String spaceId);

    /**
     * Atomically reserves one base slot: increments {@code baseSlotsUsed} only
     * while the block is OPEN and below {@code baseCapacity}.
     *
     * @return the updated block, or empty when the block filled up / locked
     *         concurrently (caller retries with another block).
     */
    Optional<PublishingBlock> tryReserveBaseSlot(String tenantId, String blockId);

    /** Atomically releases one base slot (cancel while OPEN). Never drops below 0. */
    void releaseBaseSlot(String tenantId, String blockId);

    /**
     * Atomically adds one FastPass slot while the block is OPEN.
     *
     * @return the updated block, or empty when the block locked concurrently.
     */
    Optional<PublishingBlock> tryAddFastPassSlot(String tenantId, String blockId);

    /** Atomically removes one FastPass slot (fastpass item cancelled while OPEN). */
    void releaseFastPassSlot(String tenantId, String blockId);

    /** CAS status transition. Empty when the block was not in {@code from}. */
    Optional<PublishingBlock> tryTransitionStatus(String tenantId, String blockId,
                                                  PublishingBlockStatus from,
                                                  PublishingBlockStatus to);

    // ── Platform-level scheduler sweeps (cross-tenant, called inside runWithoutTenant) ──

    /** OPEN blocks whose lockAt has passed. */
    List<PublishingBlock> findBlocksToLock(LocalDateTime now, int limit);

    /** LOCKED blocks whose publishAt has passed. */
    List<PublishingBlock> findBlocksToPublish(LocalDateTime now, int limit);
}
