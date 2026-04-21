package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Read-only access to the shared {@code tenants} collection owned by admin-api.
 */
public interface TenantReadRepository extends MongoRepository<TenantReadModel, String> {

    Optional<TenantReadModel> findBySubdomain(String subdomain);
}
