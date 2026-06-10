package org.earnlumens.mediastore.infrastructure.franchise.read;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Read-only access to the {@code franchise_user_bans} collection. Scoped by
 * {@code tenantId} (franchisor subdomain) so a ban issued by one franchisor can
 * never leak across tenants.
 */
public interface FranchiseBanReadRepository extends MongoRepository<FranchiseBanReadModel, String> {

    boolean existsByTenantIdAndUserId(String tenantId, String userId);
}
