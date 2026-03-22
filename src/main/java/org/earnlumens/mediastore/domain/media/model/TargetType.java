package org.earnlumens.mediastore.domain.media.model;

/**
 * Discriminator for orders and entitlements — whether the target is
 * an individual entry or a collection.
 */
public enum TargetType {
    ENTRY,
    COLLECTION
}
