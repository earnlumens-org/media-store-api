package org.earnlumens.mediastore.domain.media.repository;

import org.earnlumens.mediastore.domain.media.model.ModerationCase;

import java.util.List;
import java.util.Optional;

/** Repository contract for {@code moderation_cases} (DATA-MODEL.md §7d). */
public interface ModerationCaseRepository {

    ModerationCase save(ModerationCase moderationCase);

    Optional<ModerationCase> findByTenantIdAndId(String tenantId, String id);

    /** Cases of a tenant, newest first. */
    List<ModerationCase> findByTenantId(String tenantId, int limit);

    /**
     * Unresolved cases across ALL tenants (sweep input, R-20). Must be called
     * within {@code TenantContext.runWithoutTenant()}.
     */
    List<ModerationCase> findAllUnresolved(int limit);
}
