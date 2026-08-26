package org.earnlumens.mediastore.application.publishing;

import org.earnlumens.mediastore.domain.publishing.model.PublishingEntityType;

import java.time.LocalDateTime;

/**
 * Port that decouples the publishing queue from concrete entity types
 * (requirement: the block system must work for Entries/Collections today and
 * Users/Tenants in the future). One adapter per {@link PublishingEntityType};
 * the queue looks adapters up by type and rejects unsupported types.
 */
public interface SpacePublicationPort {

    /** The entity type this adapter handles. */
    PublishingEntityType entityType();

    /**
     * Validates the entity may be enqueued for a space: exists in the tenant,
     * is owned by {@code userId} and is already published on its profile page.
     *
     * @throws IllegalArgumentException with a stable error code otherwise
     */
    void validateOwnedPublishable(String tenantId, String userId, String entityId);

    /** True when the entity is already visible in the given space (dedupe / anti-spam). */
    boolean isPublishedToSpace(String tenantId, String entityId, String spaceId);

    /**
     * Makes the entity visible in the space with the given per-space
     * publication timestamp. Idempotent (safe on scheduler crash-rerun).
     *
     * @return true when the entity is now (or already was) visible in the
     *         space; false when it no longer qualifies (item is discarded).
     */
    boolean publishToSpace(String tenantId, String entityId, String spaceId, LocalDateTime spacePublishedAt);
}
