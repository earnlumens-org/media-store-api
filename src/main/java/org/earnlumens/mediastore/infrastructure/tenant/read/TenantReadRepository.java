package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the shared {@code tenants} collection owned by admin-api.
 */
public interface TenantReadRepository extends MongoRepository<TenantReadModel, String> {

    Optional<TenantReadModel> findBySubdomain(String subdomain);

    /**
     * Returns every tenant currently in {@code ACTIVE} status. Used by
     * platform-level cross-tenant maintenance jobs (badge expiration,
     * cleanup) so a single iteration covers the whole platform without
     * silently leaving secondary tenants out.
     */
    List<TenantReadModel> findByStatus(String status);
}
