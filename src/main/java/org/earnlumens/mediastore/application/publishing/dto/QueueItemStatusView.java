package org.earnlumens.mediastore.application.publishing.dto;

import java.math.BigDecimal;

/**
 * Live status of one queue item (one entity in one space's queue).
 * All instants are epoch millis UTC.
 */
public record QueueItemStatusView(
        String itemId,
        String spaceId,
        String spaceName,
        String blockId,
        long blockSequence,
        String blockStatus,
        Long blockPublishAtEpochMs,
        Long blockLockAtEpochMs,
        String status,
        boolean fastPass,
        BigDecimal priorityFeeXlm,
        /** 1-based position inside the block: frozen when LOCKED, provisional while QUEUED. */
        Integer position,
        /** Total items competing in the block. */
        int totalInBlock,
        /** Publications ahead across the whole space queue (earlier blocks + better-positioned peers). */
        long aheadInSpace,
        /** Cancel allowed only while the block is OPEN. */
        boolean canCancel,
        /** Fee boost allowed only while the block is OPEN (freezes 1 min before publication). */
        boolean canBoostFee
) {}
