package org.earnlumens.mediastore.domain.publishing.model;

/**
 * Type discriminator for entities that can be queued into a Space's
 * publishing block. The queue system is deliberately decoupled from any
 * concrete entity: adding support for a new type only requires a new
 * {@code SpacePublicationPort} adapter — the queue, block, fee and
 * FastPass mechanics are type-agnostic.
 *
 * <p>Future candidates: USER, TENANT.
 */
public enum PublishingEntityType {
    ENTRY,
    COLLECTION
}
