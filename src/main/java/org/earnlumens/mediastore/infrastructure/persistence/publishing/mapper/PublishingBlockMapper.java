package org.earnlumens.mediastore.infrastructure.persistence.publishing.mapper;

import org.earnlumens.mediastore.domain.publishing.model.PublishingBlock;
import org.earnlumens.mediastore.domain.publishing.model.PublishingBlockStatus;
import org.earnlumens.mediastore.infrastructure.persistence.publishing.entity.PublishingBlockEntity;
import org.springframework.stereotype.Component;

@Component
public class PublishingBlockMapper {

    public PublishingBlock toModel(PublishingBlockEntity e) {
        if (e == null) return null;
        PublishingBlock b = new PublishingBlock();
        b.setId(e.getId());
        b.setTenantId(e.getTenantId());
        b.setSpaceId(e.getSpaceId());
        b.setSequence(e.getSequence());
        b.setStatus(parseStatus(e.getStatus()));
        b.setBaseCapacity(e.getBaseCapacity());
        b.setBaseSlotsUsed(e.getBaseSlotsUsed());
        b.setFastPassSlots(e.getFastPassSlots());
        b.setLockAt(e.getLockAt());
        b.setPublishAt(e.getPublishAt());
        b.setCreatedAt(e.getCreatedAt());
        b.setPublishedAt(e.getPublishedAt());
        return b;
    }

    public PublishingBlockEntity toEntity(PublishingBlock b) {
        if (b == null) return null;
        PublishingBlockEntity e = new PublishingBlockEntity();
        e.setId(b.getId());
        e.setTenantId(b.getTenantId());
        e.setSpaceId(b.getSpaceId());
        e.setSequence(b.getSequence());
        e.setStatus(b.getStatus() == null ? PublishingBlockStatus.OPEN.name() : b.getStatus().name());
        e.setBaseCapacity(b.getBaseCapacity());
        e.setBaseSlotsUsed(b.getBaseSlotsUsed());
        e.setFastPassSlots(b.getFastPassSlots());
        e.setLockAt(b.getLockAt());
        e.setPublishAt(b.getPublishAt());
        e.setCreatedAt(b.getCreatedAt());
        e.setPublishedAt(b.getPublishedAt());
        return e;
    }

    private static PublishingBlockStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return PublishingBlockStatus.OPEN;
        try {
            return PublishingBlockStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            // Unknown status from a future version → treat as LOCKED (no mutations).
            return PublishingBlockStatus.LOCKED;
        }
    }
}
