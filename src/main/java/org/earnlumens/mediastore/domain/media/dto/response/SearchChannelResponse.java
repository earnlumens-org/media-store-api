package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * A single "channel" (creator) match in a search result.
 *
 * <p>Channels are derived from the tenant's PUBLISHED entries: a creator
 * becomes a channel within a tenant once they have public content there. All
 * fields are denormalized on the entry documents, so resolving a channel needs
 * no cross-collection join and stays strictly tenant-scoped.
 */
public record SearchChannelResponse(
        String username,
        String avatarUrl,
        String badge,
        long contentCount
) {}
