package org.earnlumens.mediastore.application.publishing;

import org.earnlumens.mediastore.domain.publishing.model.PublishingBlock;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlockStatus;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItem;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItemStatus;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingBlockRepository;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingQueueItemRepository;
import org.earnlumens.mediastore.infrastructure.lock.DistributedLockService;
import org.earnlumens.mediastore.infrastructure.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Drives the publishing-block lifecycle across all tenants and spaces.
 *
 * <p><b>Lock phase</b> (requirement 9): 60 s before {@code publishAt} the
 * block transitions OPEN → LOCKED, the in-block order (priority fees, ties by
 * who reached the value first, then arrival) is frozen into
 * {@code lockedPosition} and items become unmodifiable.
 *
 * <p><b>Publish phase</b>: at {@code publishAt} every LOCKED item is released
 * into the space via its {@link SpacePublicationPort}, stamped with a
 * per-space publication timestamp that encodes the block order (position 1
 * gets the newest timestamp so it tops the newest-first feed). Finally the
 * block transitions LOCKED → PUBLISHED. Both phases are idempotent, so a
 * crash mid-publish is repaired on the next cycle.
 */
@Component
public class PublishingBlockScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PublishingBlockScheduler.class);

    private static final int BATCH_LIMIT = 50;

    private final PublishingBlockRepository blockRepository;
    private final PublishingQueueItemRepository itemRepository;
    private final PublishingQueueService queueService;
    private final DistributedLockService lockService;

    public PublishingBlockScheduler(PublishingBlockRepository blockRepository,
                                    PublishingQueueItemRepository itemRepository,
                                    PublishingQueueService queueService,
                                    DistributedLockService lockService) {
        this.blockRepository = blockRepository;
        this.itemRepository = itemRepository;
        this.queueService = queueService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${mediastore.publishing.scheduler-interval-ms:5000}",
               initialDelayString = "${mediastore.publishing.scheduler-interval-ms:5000}")
    public void run() {
        if (!lockService.tryAcquire("publishing-block-scheduler", Duration.ofSeconds(4))) {
            return; // another instance is running this cycle
        }
        TenantContext.runWithoutTenant(() -> {
            try {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                lockDueBlocks(now);
                publishDueBlocks(now);
            } catch (Exception e) {
                logger.error("Publishing block scheduler cycle failed: {}", e.getMessage(), e);
            }
        });
    }

    private void lockDueBlocks(LocalDateTime now) {
        for (PublishingBlock block : blockRepository.findBlocksToLock(now, BATCH_LIMIT)) {
            Optional<PublishingBlock> locked = blockRepository.tryTransitionStatus(
                    block.getTenantId(), block.getId(),
                    PublishingBlockStatus.OPEN, PublishingBlockStatus.LOCKED);
            if (locked.isEmpty()) {
                continue; // raced with another transition
            }
            // Freeze the order: rank all QUEUED items and stamp positions.
            List<PublishingQueueItem> queued = itemRepository.findByBlockIdAndStatus(
                            block.getTenantId(), block.getId(), PublishingQueueItemStatus.QUEUED)
                    .stream()
                    .sorted(PublishingQueueService.BLOCK_ORDER)
                    .toList();
            int position = 1;
            for (PublishingQueueItem item : queued) {
                itemRepository.tryLock(block.getTenantId(), item.getId(), position++);
            }
            logger.info("[PublishingBlock] Locked block seq {} of space {} ({} item(s), tenant {})",
                    block.getSequence(), block.getSpaceId(), queued.size(), block.getTenantId());
        }
    }

    private void publishDueBlocks(LocalDateTime now) {
        for (PublishingBlock block : blockRepository.findBlocksToPublish(now, BATCH_LIMIT)) {
            List<PublishingQueueItem> locked = itemRepository.findByBlockIdAndStatus(
                    block.getTenantId(), block.getId(), PublishingQueueItemStatus.LOCKED);
            int total = locked.size();
            boolean allDone = true;
            for (PublishingQueueItem item : locked) {
                try {
                    releaseItem(block, item, total);
                } catch (Exception e) {
                    // Keep the block LOCKED so the next cycle retries this item.
                    allDone = false;
                    logger.error("[PublishingBlock] Failed to release item {} of block {}: {}",
                            item.getId(), block.getId(), e.getMessage(), e);
                }
            }
            if (allDone) {
                blockRepository.tryTransitionStatus(block.getTenantId(), block.getId(),
                        PublishingBlockStatus.LOCKED, PublishingBlockStatus.PUBLISHED);
                logger.info("[PublishingBlock] Published block seq {} of space {} ({} item(s), tenant {})",
                        block.getSequence(), block.getSpaceId(), total, block.getTenantId());
            }
        }
    }

    /**
     * Releases one item into its space. The per-space timestamp encodes the
     * frozen block order: position 1 gets {@code publishAt + (total-1) ms},
     * the last position gets {@code publishAt}, so the newest-first space
     * feed shows the block in exact position order.
     */
    private void releaseItem(PublishingBlock block, PublishingQueueItem item, int total) {
        int position = item.getLockedPosition() == null ? total : item.getLockedPosition();
        LocalDateTime spacePublishedAt = block.getPublishAt().plusNanos((total - (long) position) * 1_000_000L);
        boolean published = queueService.portFor(item.getEntityType())
                .publishToSpace(block.getTenantId(), item.getEntityId(), item.getSpaceId(), spacePublishedAt);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (published) {
            itemRepository.tryMarkPublished(block.getTenantId(), item.getId(), now);
        } else {
            // Entity no longer publishable (deleted/archived meanwhile) —
            // discard the item; the slot is spent (payments are irreversible).
            itemRepository.tryMarkDiscarded(block.getTenantId(), item.getId(), now);
            logger.info("[PublishingBlock] Discarded item {} (entity {} no longer publishable)",
                    item.getId(), item.getEntityId());
        }
    }
}
