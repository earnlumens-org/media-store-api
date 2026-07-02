package org.earnlumens.mediastore.infrastructure.reseller;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link ResellerLink}. Every finder is tenant-scoped and, where
 * mutation is possible, also owner-scoped ({@code resellerUserId}) so a caller
 * can only read or edit links they own under the current tenant.
 */
public interface ResellerLinkRepository extends MongoRepository<ResellerLink, String> {

    /** Resolve a link by its opaque code (used at purchase time). */
    Optional<ResellerLink> findByTenantIdAndCode(String tenantId, String code);

    /** The caller's existing link for an entry, if any (one-link-per-entry rule). */
    Optional<ResellerLink> findByTenantIdAndEntryIdAndResellerUserId(
            String tenantId, String entryId, String resellerUserId);

    /** All links owned by the caller under the current tenant. */
    List<ResellerLink> findByTenantIdAndResellerUserId(String tenantId, String resellerUserId);

    /** A specific owned link, for wallet edits. */
    Optional<ResellerLink> findByTenantIdAndIdAndResellerUserId(
            String tenantId, String id, String resellerUserId);
}
