package org.earnlumens.mediastore.infrastructure.persistence.plan;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PlanOrderMongoRepository extends MongoRepository<PlanOrderDocument, String> {

    Optional<PlanOrderDocument> findByIdAndTenantIdAndOwnerOauthUserId(
            String id, String tenantId, String ownerOauthUserId);

    List<PlanOrderDocument> findByTenantIdAndOwnerOauthUserIdAndStatusIn(
            String tenantId, String ownerOauthUserId, List<String> statuses);

    /** Anti-replay: a Stellar tx hash may pay for a plan at most once. */
    boolean existsByStatusAndStellarTxHashAndIdNot(String status, String stellarTxHash, String id);
}
