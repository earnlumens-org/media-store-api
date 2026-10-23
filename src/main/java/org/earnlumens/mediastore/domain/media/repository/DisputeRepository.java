package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.Dispute;
import org.earnlumens.mediastore.domain.media.model.DisputeStatus;

import java.util.List;
import java.util.Optional;

/** Repository contract for {@code disputes} (DATA-MODEL.md §7e). */
public interface DisputeRepository {

    /**
     * Opens a dispute if none exists for {@code (tenantId, moderationCaseId,
     * entitlementId)}; otherwise leaves the existing document untouched
     * (status included — an administrator's resolution is never overwritten).
     * Backed by the unique index of the same key, so concurrent callers
     * cannot create duplicates.
     *
     * @return true when a new dispute was created
     */
    boolean openIfAbsent(Dispute dispute);

    List<Dispute> findByTenantIdAndModerationCaseId(String tenantId, String moderationCaseId);

    List<Dispute> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, DisputeStatus status);

    Optional<Dispute> findByTenantIdAndId(String tenantId, String id);
}
