package org.earnlumens.mediastore.infrastructure.persistence.publishing.mapper;

import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItem;
import org.earnlumens.mediastore.domain.publishing.model.PublishingQueueItemStatus;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.entity.PublishingQueueItemEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PublishingQueueItemMapper {

    public PublishingQueueItem toModel(PublishingQueueItemEntity e) {
        if (e == null) return null;
        PublishingQueueItem i = new PublishingQueueItem();
        i.setId(e.getId());
        i.setTenantId(e.getTenantId());
        i.setSpaceId(e.getSpaceId());
        i.setBlockId(e.getBlockId());
        i.setBlockSequence(e.getBlockSequence());
        i.setBlockPublishAt(e.getBlockPublishAt());
        i.setEntityType(parseEntityType(e.getEntityType()));
        i.setEntityId(e.getEntityId());
        i.setUserId(e.getUserId());
        i.setStatus(parseStatus(e.getStatus()));
        i.setFastPass(e.isFastPass());
        i.setPriorityFeeXlm(e.getPriorityFeeXlm() == null ? BigDecimal.ZERO : e.getPriorityFeeXlm());
        i.setFeeLastIncreasedAt(e.getFeeLastIncreasedAt());
        i.setLockedPosition(e.getLockedPosition());
        i.setAppliedOrderIds(e.getAppliedOrderIds());
        i.setEnqueuedAt(e.getEnqueuedAt());
        i.setPublishedAt(e.getPublishedAt());
        i.setCancelledAt(e.getCancelledAt());
        return i;
    }

    public PublishingQueueItemEntity toEntity(PublishingQueueItem i) {
        if (i == null) return null;
        PublishingQueueItemEntity e = new PublishingQueueItemEntity();
        e.setId(i.getId());
        e.setTenantId(i.getTenantId());
        e.setSpaceId(i.getSpaceId());
        e.setBlockId(i.getBlockId());
        e.setBlockSequence(i.getBlockSequence());
        e.setBlockPublishAt(i.getBlockPublishAt());
        e.setEntityType(i.getEntityType() == null ? null : i.getEntityType().name());
        e.setEntityId(i.getEntityId());
        e.setUserId(i.getUserId());
        e.setStatus(i.getStatus() == null ? PublishingQueueItemStatus.QUEUED.name() : i.getStatus().name());
        e.setFastPass(i.isFastPass());
        e.setPriorityFeeXlm(i.getPriorityFeeXlm());
        e.setFeeLastIncreasedAt(i.getFeeLastIncreasedAt());
        e.setLockedPosition(i.getLockedPosition());
        e.setAppliedOrderIds(i.getAppliedOrderIds());
        e.setEnqueuedAt(i.getEnqueuedAt());
        e.setPublishedAt(i.getPublishedAt());
        e.setCancelledAt(i.getCancelledAt());
        return e;
    }

    private static PublishingEntityType parseEntityType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return PublishingEntityType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null; // unknown future type — callers treat as unsupported
        }
    }

    private static PublishingQueueItemStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return PublishingQueueItemStatus.QUEUED;
        try {
            return PublishingQueueItemStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return PublishingQueueItemStatus.CANCELLED;
        }
    }
}
