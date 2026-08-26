package org.earnlumens.mediastore.application.publishing;

import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntryStatus;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link SpacePublicationPort} adapter for ENTRY. An entry can be enqueued to
 * a space only after it is PUBLISHED on the creator's profile page
 * (requirement 1: profile publication is immediate; space publication goes
 * through the block queue).
 */
@Component
public class EntrySpacePublicationAdapter implements SpacePublicationPort {

    private final EntryRepository entryRepository;

    public EntrySpacePublicationAdapter(EntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Override
    public PublishingEntityType entityType() {
        return PublishingEntityType.ENTRY;
    }

    @Override
    public void validateOwnedPublishable(String tenantId, String userId, String entityId) {
        Entry entry = entryRepository.findByTenantIdAndId(tenantId, entityId)
                .orElseThrow(() -> new IllegalArgumentException("ENTITY_NOT_FOUND"));
        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("NOT_OWNER");
        }
        if (entry.getStatus() != EntryStatus.PUBLISHED) {
            throw new IllegalArgumentException("ENTITY_NOT_PUBLISHED");
        }
    }

    @Override
    public boolean isPublishedToSpace(String tenantId, String entityId, String spaceId) {
        return entryRepository.findByTenantIdAndId(tenantId, entityId)
                .map(e -> e.getSpaceIds() != null && e.getSpaceIds().contains(spaceId))
                .orElse(false);
    }

    @Override
    public boolean publishToSpace(String tenantId, String entityId, String spaceId,
                                  LocalDateTime spacePublishedAt) {
        return entryRepository.addSpacePublication(tenantId, entityId, spaceId, spacePublishedAt);
    }
}
