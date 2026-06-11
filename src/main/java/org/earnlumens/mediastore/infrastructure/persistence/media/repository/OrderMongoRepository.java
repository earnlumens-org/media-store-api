package org.earnlumens.mediastore.infrastructure.persistence.media.repository;

import org.earnlumens.mediastore.infrastructure.persistence.media.entity.OrderEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {

    List<OrderEntity> findByTenantIdAndUserIdAndEntryId(String tenantId, String userId, String entryId);

    List<OrderEntity> findByTenantIdAndUserIdAndCollectionId(String tenantId, String userId, String collectionId);

    List<OrderEntity> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status);

    Optional<OrderEntity> findByTenantIdAndId(String tenantId, String id);

    List<OrderEntity> findByTenantIdAndStatusAndExpiresAtBefore(String tenantId, String status, LocalDateTime cutoff);

    long countByTenantIdAndSellerIdAndStatus(String tenantId, String sellerId, String status);

    List<OrderEntity> findByTenantIdAndSellerIdAndStatusOrderByCompletedAtDesc(String tenantId, String sellerId, String status);

    /**
     * Anti-replay check: does any OTHER order already hold this Stellar tx hash in the given status?
     * Tx hashes are globally unique on-chain, so the check is deliberately cross-tenant.
     */
    boolean existsByStellarTxHashAndStatusAndIdNot(String stellarTxHash, String status, String id);

    // ── Payment reconciliation watchdog queries (deliberately cross-tenant) ──

    /** Orders in a status whose tx window closed before the cutoff (stale PROCESSING detection). */
    List<OrderEntity> findByStatusAndExpiresAtBefore(String status, LocalDateTime cutoff, Pageable pageable);

    /** Orders in a status (FAILED re-check scan). */
    List<OrderEntity> findByStatus(String status, Pageable pageable);

    /** Recently completed orders (missing-entitlement repair scan). */
    List<OrderEntity> findByStatusAndCompletedAtAfter(String status, LocalDateTime cutoff, Pageable pageable);
}
