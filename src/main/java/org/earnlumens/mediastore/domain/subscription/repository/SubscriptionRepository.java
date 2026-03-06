package org.earnlumens.mediastore.domain.subscription.repository;

import org.earnlumens.mediastore.domain.subscription.model.Subscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Port interface for subscription persistence.
 * Designed for bidirectional queries (who I follow / who follows me)
 * and efficient count operations for scalable social features.
 */
public interface SubscriptionRepository {

    /** Check if a specific subscription relationship exists. */
    boolean existsByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId);

    /** Find a specific subscription by subscriber + target. */
    Optional<Subscription> findByTenantIdAndSubscriberIdAndTargetUserId(String tenantId, String subscriberId, String targetUserId);

    /** List users I am subscribed to, ordered by most recent first. */
    Page<Subscription> findByTenantIdAndSubscriberId(String tenantId, String subscriberId, Pageable pageable);

    /** List users subscribed to a target (my subscribers), ordered by most recent first. */
    Page<Subscription> findByTenantIdAndTargetUserId(String tenantId, String targetUserId, Pageable pageable);

    /** Count how many subscribers a user has. */
    long countByTenantIdAndTargetUserId(String tenantId, String targetUserId);

    /** Count how many users someone is subscribed to. */
    long countByTenantIdAndSubscriberId(String tenantId, String subscriberId);

    /** Check which target user IDs the subscriber is subscribed to (batch check). */
    Set<String> findSubscribedTargetIds(String tenantId, String subscriberId, List<String> targetUserIds);

    Subscription save(Subscription subscription);

    void deleteById(String id);
}
