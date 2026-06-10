package org.earnlumens.mediastore.infrastructure.franchise.read;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the {@code franchises} collection. Every query is scoped
 * by {@code tenantId} (the franchisor subdomain) to satisfy tenant isolation.
 */
public interface FranchiseReadRepository extends MongoRepository<FranchiseReadModel, String> {

    Optional<FranchiseReadModel> findByTenantIdAndSlug(String tenantId, String slug);

    Optional<FranchiseReadModel> findByTenantIdAndId(String tenantId, String id);

    List<FranchiseReadModel> findByTenantIdAndStatus(String tenantId, String status);
}
