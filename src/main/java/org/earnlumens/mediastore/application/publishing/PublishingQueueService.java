package org.earnlumens.mediastore.application.publishing;

import org.earnlumens.mediastore.application.publishing.dto.QueueItemStatusView;
import org.earnlumens.mediastore.application.publishing.dto.SpaceQueuePreview;
import org.earnlumens.mediastore.application.space.SpaceValidationService;
import org.earnlumens.mediastore.domain.media.model.Order;
import org.earnlumens.mediastore.domain.media.model.TargetType;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlock;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlockStatus;
import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItem;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItemStatus;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingBlockRepository;
import org.earnlumens.mediastore.domain.publishing.repository.PublishingQueueItemRepository;
import org.earnlumens.mediastore.domain.space.Space;
import org.earnlumens.mediastore.domain.space.repository.SpaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Publishing Block queue (see requirements in the Publishing Block feature):
 * approved entities publish immediately on the creator's profile; visibility
 * in a Space goes through per-space blocks of {@code 48} base slots released
 * every {@code 10} minutes (both configurable per space).
 *
 * <ul>
 *   <li>Enqueue assigns the entity definitively to the earliest OPEN block
 *       with a free base slot (requirement 4 — assignment is irreversible;
 *       cancelling frees the slot but never shifts other blocks).</li>
 *   <li>Priority fees reorder items <i>within</i> their block only; ties are
 *       won by whoever reached the fee value first (requirement 6).</li>
 *   <li>FastPass buys an extra slot in the NEXT open block, only when the
 *       base slots are sold out (requirement 7).</li>
 *   <li>One active item per entity per space, and an entity already visible
 *       in a space can never be re-enqueued to it (anti-spam).</li>
 * </ul>
 */
@Service
public class PublishingQueueService {

    private static final Logger logger = LoggerFactory.getLogger(PublishingQueueService.class);

    /** Blocks lock 60s before publication (requirement 9). */
    public static final long LOCK_LEAD_SECONDS = 60;

    private static final int BLOCK_CREATE_RETRIES = 5;

    /**
     * In-block ordering (requirement 6): highest cumulative fee first; fee
     * ties go to whoever reached the value first; the no-fee tail is ordered
     * by arrival. Id is the final deterministic tiebreaker.
     */
    static final Comparator<PublishingQueueItem> BLOCK_ORDER = (a, b) -> {
        BigDecimal feeA = a.getPriorityFeeXlm() == null ? BigDecimal.ZERO : a.getPriorityFeeXlm();
        BigDecimal feeB = b.getPriorityFeeXlm() == null ? BigDecimal.ZERO : b.getPriorityFeeXlm();
        int byFee = feeB.compareTo(feeA);
        if (byFee != 0) return byFee;
        if (feeA.signum() > 0) {
            LocalDateTime ta = a.getFeeLastIncreasedAt() == null ? a.getEnqueuedAt() : a.getFeeLastIncreasedAt();
            LocalDateTime tb = b.getFeeLastIncreasedAt() == null ? b.getEnqueuedAt() : b.getFeeLastIncreasedAt();
            int byReached = compareNullable(ta, tb);
            if (byReached != 0) return byReached;
        }
        int byArrival = compareNullable(a.getEnqueuedAt(), b.getEnqueuedAt());
        if (byArrival != 0) return byArrival;
        return a.getId().compareTo(b.getId());
    };

    private static int compareNullable(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    private final PublishingBlockRepository blockRepository;
    private final PublishingQueueItemRepository itemRepository;
    private final SpaceRepository spaceRepository;
    private final SpaceValidationService spaceValidationService;
    private final Map<PublishingEntityType, SpacePublicationPort> portsByType;

    public PublishingQueueService(PublishingBlockRepository blockRepository,
                                  PublishingQueueItemRepository itemRepository,
                                  SpaceRepository spaceRepository,
                                  SpaceValidationService spaceValidationService,
                                  List<SpacePublicationPort> publicationPorts) {
        this.blockRepository = blockRepository;
        this.itemRepository = itemRepository;
        this.spaceRepository = spaceRepository;
        this.spaceValidationService = spaceValidationService;
        this.portsByType = publicationPorts.stream()
                .collect(Collectors.toMap(SpacePublicationPort::entityType, Function.identity()));
    }

    /** Adapter lookup; unsupported types are rejected (system stays decoupled). */
    SpacePublicationPort portFor(PublishingEntityType entityType) {
        SpacePublicationPort port = entityType == null ? null : portsByType.get(entityType);
        if (port == null) {
            throw new IllegalArgumentException("ENTITY_TYPE_NOT_SUPPORTED");
        }
        return port;
    }

    // ─────────────────────────────────────────────────────────── enqueue ──

    /**
     * Enqueues an owned, profile-published entity into the queues of the given
     * spaces. Each space gets its own independent item/block (requirement 8).
     * Spaces where the entity is already visible or already queued are
     * rejected with a stable error code.
     */
    public List<QueueItemStatusView> enqueue(String tenantId, String userId,
                                             PublishingEntityType entityType, String entityId,
                                             List<String> spaceIds) {
        SpacePublicationPort port = portFor(entityType);
        port.validateOwnedPublishable(tenantId, userId, entityId);

        List<String> validated = spaceValidationService.validateForPublish(tenantId, userId, spaceIds);
        if (validated.isEmpty()) {
            throw new IllegalArgumentException("NO_SPACES_SELECTED");
        }
        Map<String, Space> spaces = loadSpaces(tenantId, validated);

        // Validate ALL targets before creating ANY item (all-or-nothing UX).
        for (String spaceId : validated) {
            Space space = spaces.get(spaceId);
            if (space.isSystemSpace()) {
                throw new IllegalArgumentException("SYSTEM_SPACE_NOT_ALLOWED");
            }
            if (port.isPublishedToSpace(tenantId, entityId, spaceId)) {
                throw new IllegalStateException("ALREADY_PUBLISHED_TO_SPACE");
            }
            if (itemRepository.findActiveByEntityAndSpace(tenantId, entityType, entityId, spaceId).isPresent()) {
                throw new IllegalStateException("ALREADY_QUEUED");
            }
        }

        List<QueueItemStatusView> results = new ArrayList<>();
        for (String spaceId : validated) {
            PublishingQueueItem item = enqueueIntoSpace(tenantId, userId, entityType, entityId,
                    spaces.get(spaceId), false);
            results.add(toStatusView(tenantId, item, spaces.get(spaceId)));
        }
        return results;
    }

    /**
     * Reserves a base slot (or a FastPass slot when {@code fastPass}) in the
     * earliest OPEN block of the space, creating new blocks as needed, and
     * persists the queue item. Requirement 4: the block assignment is final.
     */
    private PublishingQueueItem enqueueIntoSpace(String tenantId, String userId,
                                                 PublishingEntityType entityType, String entityId,
                                                 Space space, boolean fastPass) {
        String spaceId = space.getId();
        for (int attempt = 0; attempt < BLOCK_CREATE_RETRIES; attempt++) {
            PublishingBlock block = fastPass
                    ? blockRepository.findEarliestOpenBlock(tenantId, spaceId)
                            .orElseGet(() -> createNextBlock(tenantId, space))
                    : blockRepository.findEarliestOpenBlockWithFreeBaseSlot(tenantId, spaceId)
                            .orElseGet(() -> createNextBlock(tenantId, space));

            Optional<PublishingBlock> reserved = fastPass
                    ? blockRepository.tryAddFastPassSlot(tenantId, block.getId())
                    : blockRepository.tryReserveBaseSlot(tenantId, block.getId());
            if (reserved.isEmpty()) {
                continue; // filled or locked concurrently → retry with the next block
            }
            PublishingBlock target = reserved.get();

            PublishingQueueItem item = new PublishingQueueItem();
            item.setTenantId(tenantId);
            item.setSpaceId(spaceId);
            item.setBlockId(target.getId());
            item.setBlockSequence(target.getSequence());
            item.setBlockPublishAt(target.getPublishAt());
            item.setEntityType(entityType);
            item.setEntityId(entityId);
            item.setUserId(userId);
            item.setStatus(PublishingQueueItemStatus.QUEUED);
            item.setFastPass(fastPass);
            item.setPriorityFeeXlm(BigDecimal.ZERO);
            item.setEnqueuedAt(LocalDateTime.now(ZoneOffset.UTC));
            try {
                return itemRepository.save(item);
            } catch (DuplicateKeyException e) {
                // Unique active index fired: a concurrent request queued the
                // same entity to this space first. Release our slot and fail.
                if (fastPass) {
                    blockRepository.releaseFastPassSlot(tenantId, target.getId());
                } else {
                    blockRepository.releaseBaseSlot(tenantId, target.getId());
                }
                throw new IllegalStateException("ALREADY_QUEUED");
            }
        }
        throw new IllegalStateException("QUEUE_CONTENTION");
    }

    /**
     * Creates the next block of the space (sequence = latest + 1). The block
     * chain is gapless in time: each block publishes {@code interval} minutes
     * after the previous one (or after now for an idle space). Unique index
     * on (tenant, space, sequence) resolves concurrent creators.
     */
    private PublishingBlock createNextBlock(String tenantId, Space space) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int attempt = 0; attempt < BLOCK_CREATE_RETRIES; attempt++) {
            Optional<PublishingBlock> latest = blockRepository.findLatestBlock(tenantId, space.getId());
            long nextSequence = latest.map(b -> b.getSequence() + 1).orElse(1L);
            LocalDateTime base = latest.map(PublishingBlock::getPublishAt)
                    .filter(t -> t.isAfter(now))
                    .orElse(now);
            LocalDateTime publishAt = base.plusMinutes(space.effectiveBlockIntervalMinutes());
            PublishingBlock block = new PublishingBlock();
            block.setTenantId(tenantId);
            block.setSpaceId(space.getId());
            block.setSequence(nextSequence);
            block.setStatus(PublishingBlockStatus.OPEN);
            block.setBaseCapacity(space.effectiveBlockSize());
            block.setBaseSlotsUsed(0);
            block.setFastPassSlots(0);
            block.setPublishAt(publishAt);
            block.setLockAt(publishAt.minusSeconds(LOCK_LEAD_SECONDS));
            block.setCreatedAt(now);
            try {
                return blockRepository.save(block);
            } catch (DuplicateKeyException e) {
                // Concurrent creation of the same sequence → reload and either
                // reuse the winner (still open) or compute the next sequence.
                Optional<PublishingBlock> winner = blockRepository
                        .findEarliestOpenBlock(tenantId, space.getId());
                if (winner.isPresent()) {
                    return winner.get();
                }
            }
        }
        throw new IllegalStateException("QUEUE_CONTENTION");
    }

    // ─────────────────────────────────────────────────────────── preview ──

    /**
     * Pre-enqueue snapshot for every candidate space of the entity: which
     * block it would join, when it publishes and how many are ahead.
     */
    public List<SpaceQueuePreview> previewSpaces(String tenantId, String userId,
                                                 PublishingEntityType entityType, String entityId) {
        SpacePublicationPort port = portFor(entityType);
        port.validateOwnedPublishable(tenantId, userId, entityId);

        List<Space> candidates = spaceRepository.findSidebarSpaces(tenantId).stream()
                .filter(s -> !s.isSystemSpace())
                .filter(Space::isAllowPublishing)
                .toList();

        List<SpaceQueuePreview> previews = new ArrayList<>();
        for (Space space : candidates) {
            boolean alreadyPublished = port.isPublishedToSpace(tenantId, entityId, space.getId());
            Optional<PublishingQueueItem> active = itemRepository
                    .findActiveByEntityAndSpace(tenantId, entityType, entityId, space.getId());

            PublishingBlock next = blockRepository
                    .findEarliestOpenBlockWithFreeBaseSlot(tenantId, space.getId())
                    .orElse(null);
            // FastPass is purchasable only when every base slot of the
            // earliest open block is taken (requirement 7).
            boolean fastPassAvailable = next == null
                    && blockRepository.findEarliestOpenBlock(tenantId, space.getId()).isPresent();

            long waitingAhead = itemRepository.countActiveBySpace(tenantId, space.getId());
            LocalDateTime publishAt;
            LocalDateTime lockAt;
            long sequence;
            int capacity;
            int used;
            int fastPassSlots;
            if (next != null) {
                publishAt = next.getPublishAt();
                lockAt = next.getLockAt();
                sequence = next.getSequence();
                capacity = next.getBaseCapacity();
                used = next.getBaseSlotsUsed();
                fastPassSlots = next.getFastPassSlots();
            } else {
                // No open block with room: simulate where a new block would land.
                Optional<PublishingBlock> latest = blockRepository.findLatestBlock(tenantId, space.getId());
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                LocalDateTime base = latest.map(PublishingBlock::getPublishAt)
                        .filter(t -> t.isAfter(now)).orElse(now);
                publishAt = base.plusMinutes(space.effectiveBlockIntervalMinutes());
                lockAt = publishAt.minusSeconds(LOCK_LEAD_SECONDS);
                sequence = latest.map(b -> b.getSequence() + 1).orElse(1L);
                capacity = space.effectiveBlockSize();
                used = 0;
                fastPassSlots = 0;
            }

            previews.add(new SpaceQueuePreview(
                    space.getId(),
                    space.getBaseName(),
                    space.getIcon(),
                    sequence,
                    toEpochMs(publishAt),
                    toEpochMs(lockAt),
                    capacity,
                    used,
                    fastPassSlots,
                    waitingAhead,
                    fastPassAvailable,
                    space.effectiveFastPassPriceUsd(),
                    alreadyPublished,
                    active.isPresent(),
                    active.map(PublishingQueueItem::getId).orElse(null)));
        }
        return previews;
    }

    // ──────────────────────────────────────────────────────────── status ──

    /** Live status of every queue item of the entity (all spaces, newest first). */
    public List<QueueItemStatusView> getQueueStatus(String tenantId, String userId,
                                                    PublishingEntityType entityType, String entityId) {
        List<PublishingQueueItem> items = itemRepository.findByEntity(tenantId, entityType, entityId).stream()
                .filter(i -> i.getUserId().equals(userId))
                .toList();
        if (items.isEmpty()) return List.of();

        Set<String> spaceIds = items.stream().map(PublishingQueueItem::getSpaceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Space> spaces = spaceRepository
                .findByTenantIdAndIdIn(tenantId, List.copyOf(spaceIds)).stream()
                .collect(Collectors.toMap(Space::getId, s -> s));

        return items.stream()
                .map(i -> toStatusView(tenantId, i, spaces.get(i.getSpaceId())))
                .toList();
    }

    private QueueItemStatusView toStatusView(String tenantId, PublishingQueueItem item, Space space) {
        PublishingBlock block = blockRepository.findByTenantIdAndId(tenantId, item.getBlockId()).orElse(null);
        String blockStatus = block == null ? null : block.getStatus().name();
        boolean open = block != null && block.getStatus() == PublishingBlockStatus.OPEN;

        Integer position = item.getLockedPosition();
        int totalInBlock = 0;
        long aheadInSpace = 0;
        if (item.isActive()) {
            List<PublishingQueueItem> peers = itemRepository.findByBlockIdAndStatusIn(
                    tenantId, item.getBlockId(),
                    List.of(PublishingQueueItemStatus.QUEUED, PublishingQueueItemStatus.LOCKED));
            totalInBlock = peers.size();
            if (position == null) {
                // Provisional position: where the item ranks right now.
                List<PublishingQueueItem> ordered = peers.stream().sorted(BLOCK_ORDER).toList();
                for (int i = 0; i < ordered.size(); i++) {
                    if (ordered.get(i).getId().equals(item.getId())) {
                        position = i + 1;
                        break;
                    }
                }
            }
            long earlierBlocks = itemRepository.countActiveBySpaceBeforeSequence(
                    tenantId, item.getSpaceId(), item.getBlockSequence());
            aheadInSpace = earlierBlocks + (position == null ? 0 : position - 1);
        }

        return new QueueItemStatusView(
                item.getId(),
                item.getSpaceId(),
                space == null ? null : space.getBaseName(),
                item.getBlockId(),
                item.getBlockSequence(),
                blockStatus,
                toEpochMs(block == null ? item.getBlockPublishAt() : block.getPublishAt()),
                toEpochMs(block == null ? null : block.getLockAt()),
                item.getStatus().name(),
                item.isFastPass(),
                item.getPriorityFeeXlm(),
                position,
                totalInBlock,
                aheadInSpace,
                item.getStatus() == PublishingQueueItemStatus.QUEUED && open,
                item.getStatus() == PublishingQueueItemStatus.QUEUED && open);
    }

    // ──────────────────────────────────────────────────────────── cancel ──

    /**
     * Cancels a QUEUED item and frees its slot. Only allowed while the block
     * is OPEN (once LOCKED the order is frozen — requirement 9). Fees and
     * FastPass payments are never refunded (requirement 10).
     */
    public void cancel(String tenantId, String userId, String itemId) {
        PublishingQueueItem item = itemRepository.findByTenantIdAndId(tenantId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("QUEUE_ITEM_NOT_FOUND"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("NOT_OWNER");
        }
        PublishingBlock block = blockRepository.findByTenantIdAndId(tenantId, item.getBlockId())
                .orElseThrow(() -> new IllegalStateException("BLOCK_NOT_FOUND"));
        if (block.getStatus() != PublishingBlockStatus.OPEN) {
            throw new IllegalStateException("BLOCK_LOCKED");
        }
        Optional<PublishingQueueItem> cancelled = itemRepository.tryCancel(
                tenantId, itemId, userId, LocalDateTime.now(ZoneOffset.UTC));
        if (cancelled.isEmpty()) {
            throw new IllegalStateException("BLOCK_LOCKED");
        }
        // Free the slot without moving anyone else (requirement 4).
        if (cancelled.get().isFastPass()) {
            blockRepository.releaseFastPassSlot(tenantId, item.getBlockId());
        } else {
            blockRepository.releaseBaseSlot(tenantId, item.getBlockId());
        }
    }

    // ─────────────────────────────────────────── payment effects (hooks) ──

    /**
     * Pre-payment validation for a Publish Priority Fee: the item must belong
     * to the caller, still be QUEUED and its block still OPEN (fees can be
     * increased only until 1 minute before publication — requirement 6/9).
     */
    public PublishingQueueItem requireBoostableItem(String tenantId, String userId, String itemId) {
        PublishingQueueItem item = itemRepository.findByTenantIdAndId(tenantId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("QUEUE_ITEM_NOT_FOUND"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("NOT_OWNER");
        }
        if (item.getStatus() != PublishingQueueItemStatus.QUEUED) {
            throw new IllegalStateException("BLOCK_LOCKED");
        }
        PublishingBlock block = blockRepository.findByTenantIdAndId(tenantId, item.getBlockId())
                .orElseThrow(() -> new IllegalStateException("BLOCK_NOT_FOUND"));
        if (block.getStatus() != PublishingBlockStatus.OPEN) {
            throw new IllegalStateException("BLOCK_LOCKED");
        }
        return item;
    }

    /**
     * Pre-payment validation for a FastPass purchase: entity owned and
     * profile-published, space valid, not already published/queued there, and
     * FastPass enabled — i.e. the earliest OPEN block's base slots are all
     * taken (requirement 7). Returns the space (for its FastPass price).
     */
    public Space requireFastPassTarget(String tenantId, String userId,
                                       PublishingEntityType entityType, String entityId,
                                       String spaceId) {
        SpacePublicationPort port = portFor(entityType);
        port.validateOwnedPublishable(tenantId, userId, entityId);
        spaceValidationService.validateForPublish(tenantId, userId, List.of(spaceId));
        Space space = spaceRepository.findByTenantIdAndId(tenantId, spaceId)
                .orElseThrow(() -> new IllegalArgumentException("SPACE_NOT_FOUND"));
        if (space.isSystemSpace()) {
            throw new IllegalArgumentException("SYSTEM_SPACE_NOT_ALLOWED");
        }
        if (port.isPublishedToSpace(tenantId, entityId, spaceId)) {
            throw new IllegalStateException("ALREADY_PUBLISHED_TO_SPACE");
        }
        if (itemRepository.findActiveByEntityAndSpace(tenantId, entityType, entityId, spaceId).isPresent()) {
            throw new IllegalStateException("ALREADY_QUEUED");
        }
        // FastPass is only sellable while the base slots are sold out; with a
        // free base slot the user can simply enqueue for free.
        boolean baseFull = blockRepository.findEarliestOpenBlock(tenantId, spaceId).isPresent()
                && blockRepository.findEarliestOpenBlockWithFreeBaseSlot(tenantId, spaceId).isEmpty();
        if (!baseFull) {
            throw new IllegalStateException("FAST_PASS_NOT_AVAILABLE");
        }
        return space;
    }

    /**
     * Applies a COMPLETED publishing order. Called by the payment pipeline on
     * confirmation AND by the reconciliation watchdog — both paths are
     * idempotent (fee: appliedOrderIds guard; FastPass: active-item dedupe).
     */
    public void applyPaymentEffect(Order order) {
        if (order.getTargetType() == TargetType.PUBLISH_FEE) {
            applyFeePayment(order);
        } else if (order.getTargetType() == TargetType.PUBLISH_FAST_PASS) {
            applyFastPassPayment(order);
        }
    }

    /**
     * Requirement 6: cumulative fee, applied atomically. If the payment
     * confirms after the block locked, the fee is still recorded (payments
     * are irreversible) but the frozen order does not change.
     */
    private void applyFeePayment(Order order) {
        Optional<PublishingQueueItem> updated = itemRepository.tryApplyFeePayment(
                order.getTenantId(), order.getPublishQueueItemId(), order.getId(),
                order.getAmountXlm(), LocalDateTime.now(ZoneOffset.UTC));
        if (updated.isEmpty()) {
            logger.info("[PublishingQueue] Fee order {} already applied (or item {} missing) — idempotent no-op",
                    order.getId(), order.getPublishQueueItemId());
        } else {
            logger.info("[PublishingQueue] Applied fee {} XLM to item {} (total now {})",
                    order.getAmountXlm(), order.getPublishQueueItemId(),
                    updated.get().getPriorityFeeXlm());
        }
    }

    /**
     * Requirement 7: FastPass guarantees INCLUSION in the next open block
     * (adds one slot), never position. If the next block locked while the
     * payment settled, the item lands in the following block.
     */
    private void applyFastPassPayment(Order order) {
        String tenantId = order.getTenantId();
        PublishingEntityType entityType = PublishingEntityType.valueOf(order.getPublishEntityType());
        String entityId = order.getPublishEntityId();
        String spaceId = order.getPublishSpaceId();

        // Idempotency / dedupe: skip when an active item already exists or the
        // entity is already visible in the space.
        if (itemRepository.findActiveByEntityAndSpace(tenantId, entityType, entityId, spaceId).isPresent()) {
            logger.info("[PublishingQueue] FastPass order {}: entity already queued — idempotent no-op", order.getId());
            return;
        }
        SpacePublicationPort port = portFor(entityType);
        if (port.isPublishedToSpace(tenantId, entityId, spaceId)) {
            logger.info("[PublishingQueue] FastPass order {}: entity already published to space — no-op", order.getId());
            return;
        }
        Space space = spaceRepository.findByTenantIdAndId(tenantId, spaceId)
                .orElseThrow(() -> new IllegalStateException("SPACE_NOT_FOUND"));
        PublishingQueueItem item = enqueueIntoSpace(tenantId, order.getUserId(),
                entityType, entityId, space, true);
        logger.info("[PublishingQueue] FastPass order {} placed entity {} into block seq {} of space {}",
                order.getId(), entityId, item.getBlockSequence(), spaceId);
    }

    // ─────────────────────────────────────────────────────────── helpers ──

    private Map<String, Space> loadSpaces(String tenantId, List<String> spaceIds) {
        Map<String, Space> spaces = new HashMap<>();
        for (Space s : spaceRepository.findByTenantIdAndIdIn(tenantId, spaceIds)) {
            spaces.put(s.getId(), s);
        }
        if (spaces.size() != spaceIds.size()) {
            throw new IllegalArgumentException("SPACE_NOT_FOUND");
        }
        return spaces;
    }

    static Long toEpochMs(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
