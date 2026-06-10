package org.earnlumens.mediastore.infrastructure.franchise.write;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Write access to the {@code franchises} collection for the franchisee
 * self-service flow. Every finder is scoped by {@code tenantId} (the franchisor
 * subdomain) AND, where it matters, by {@code ownerOauthUserId}, so a caller can
 * only ever read or mutate a franchise they own under the current tenant.
 *
 * <p>This repository deliberately lives outside {@code domain} (mirroring the
 * read side under {@code infrastructure.franchise}) because the {@code franchises}
 * collection is co-owned with admin-api rather than a media-store-api aggregate.
 */
public interface FranchiseWriteRepository extends MongoRepository<FranchiseWriteModel, String> {

    boolean existsByTenantIdAndSlug(String tenantId, String slug);

    List<FranchiseWriteModel> findByTenantIdAndOwnerOauthUserId(String tenantId, String ownerOauthUserId);

    Optional<FranchiseWriteModel> findByTenantIdAndIdAndOwnerOauthUserId(
        String tenantId, String id, String ownerOauthUserId);
}
