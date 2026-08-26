package org.earnlumens.mediastore.application.publishing.dto;

import java.math.BigDecimal;

/**
 * Battlefield-style queue snapshot for one candidate space, shown to the user
 * BEFORE enqueuing: which block their entity would enter, when it publishes,
 * how full it is and how many publications are ahead of them.
 * All instants are epoch millis UTC (timezone-safe on the wire).
 */
public record SpaceQueuePreview(
        String spaceId,
        String spaceName,
        String spaceIcon,
        /** Sequence of the block the entity would join (next open block with a free base slot). */
        long nextBlockSequence,
        Long nextBlockPublishAtEpochMs,
        Long nextBlockLockAtEpochMs,
        int baseCapacity,
        int baseSlotsUsed,
        int fastPassSlots,
        /** Active items in the whole space queue = publications ahead of a new enqueue. */
        long waitingAhead,
        /** FastPass purchasable only when the earliest open block's base slots are full. */
        boolean fastPassAvailable,
        BigDecimal fastPassPriceUsd,
        /** Entity is already visible in this space (enqueue blocked \u2014 anti-spam). */
        boolean alreadyPublished,
        /** Entity already has an active queue item for this space. */
        boolean alreadyQueued,
        /** Id of the active queue item when alreadyQueued. */
        String queueItemId
) {}
