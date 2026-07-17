package org.earnlumens.mediastore.infrastructure.original;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;

/**
 * Spring Data repository for Original First claim audit records.
 * Follows the same infrastructure-level pattern as the reseller repositories.
 */
public interface OriginalClaimRepository extends MongoRepository<OriginalClaimDocument, String> {

    boolean existsByTenantIdAndFingerprintAndClaimantUserId(String tenantId, String fingerprint, String claimantUserId);

    /** Daily anti-abuse quota: claims filed by a user in a rolling window. */
    long countByTenantIdAndClaimantUserIdAndCreatedAtAfter(String tenantId, String claimantUserId, LocalDateTime after);
}
