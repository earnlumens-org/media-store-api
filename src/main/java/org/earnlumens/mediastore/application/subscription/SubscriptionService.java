package org.earnlumens.mediastore.application.subscription;

import org.earnlumens.mediastore.domain.subscription.model.Subscription;
import org.earnlumens.mediastore.domain.subscription.repository.SubscriptionRepository;
import org.earnlumens.mediastore.domain.user.model.User;
import org.earnlumens.mediastore.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Application service for managing user subscriptions.
 *
 * <p>Handles subscribe/unsubscribe with idempotency, self-subscription prevention,
 * bidirectional listing (my subscriptions / my subscribers), and subscriber counts.</p>
 */
@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Subscribe the current user to a target user.
     *
     * @return true if newly subscribed, false if already subscribed (idempotent)
     * @throws IllegalArgumentException if self-subscription or target not found
     */
    public boolean subscribe(String tenantId, String subscriberId, String targetUserId) {
        if (subscriberId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot subscribe to yourself");
        }

        // Idempotent: if already subscribed, return false
        if (subscriptionRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(tenantId, subscriberId, targetUserId)) {
            logger.debug("Already subscribed: subscriber={} target={}", subscriberId, targetUserId);
            return false;
        }

        // Resolve both users for denormalized fields.
        // subscriberId comes from extractUserId() which is the OAuth provider ID (JWT subject),
        // NOT the MongoDB document _id, so we must look up by oauthUserId.
        User subscriber = userRepository.findByOauthUserId(subscriberId)
                .orElseThrow(() -> new IllegalArgumentException("Subscriber user not found"));
        User target = userRepository.findByOauthUserId(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Target user not found"));

        Subscription subscription = new Subscription();
        subscription.setTenantId(tenantId);
        subscription.setSubscriberId(subscriberId);
        subscription.setSubscriberUsername(subscriber.getUsername());
        subscription.setSubscriberDisplayName(subscriber.getDisplayName());
        subscription.setSubscriberAvatarUrl(subscriber.getProfileImageUrl());
        subscription.setTargetUserId(targetUserId);
        subscription.setTargetUsername(target.getUsername());
        subscription.setTargetDisplayName(target.getDisplayName());
        subscription.setTargetAvatarUrl(target.getProfileImageUrl());

        subscriptionRepository.save(subscription);
        logger.info("User {} subscribed to {}", subscriberId, targetUserId);
        return true;
    }

    /**
     * Unsubscribe the current user from a target user.
     *
     * @return true if was subscribed and now removed, false if was not subscribed (idempotent)
     */
    public boolean unsubscribe(String tenantId, String subscriberId, String targetUserId) {
        Optional<Subscription> existing = subscriptionRepository
                .findByTenantIdAndSubscriberIdAndTargetUserId(tenantId, subscriberId, targetUserId);

        if (existing.isEmpty()) {
            logger.debug("Not subscribed: subscriber={} target={}", subscriberId, targetUserId);
            return false;
        }

        subscriptionRepository.deleteById(existing.get().getId());
        logger.info("User {} unsubscribed from {}", subscriberId, targetUserId);
        return true;
    }

    /**
     * Check if the current user is subscribed to a target user.
     */
    public boolean isSubscribed(String tenantId, String subscriberId, String targetUserId) {
        return subscriptionRepository.existsByTenantIdAndSubscriberIdAndTargetUserId(tenantId, subscriberId, targetUserId);
    }

    /**
     * Get subscriber count for a user (public metric).
     */
    public long getSubscriberCount(String tenantId, String targetUserId) {
        return subscriptionRepository.countByTenantIdAndTargetUserId(tenantId, targetUserId);
    }

    /**
     * Get subscription count for a user (how many they follow).
     */
    public long getSubscriptionCount(String tenantId, String subscriberId) {
        return subscriptionRepository.countByTenantIdAndSubscriberId(tenantId, subscriberId);
    }

    /**
     * List users the current user is subscribed to (my subscriptions).
     */
    public SubscriptionPageResponse listMySubscriptions(String tenantId, String subscriberId, int page, int size) {
        Page<Subscription> subscriptionPage = subscriptionRepository.findByTenantIdAndSubscriberId(
                tenantId, subscriberId, PageRequest.of(page, size));

        List<SubscriptionUserResponse> items = subscriptionPage.getContent().stream()
                .map(s -> new SubscriptionUserResponse(
                        s.getTargetUserId(),
                        s.getTargetUsername(),
                        s.getTargetDisplayName(),
                        s.getTargetAvatarUrl(),
                        s.getSubscribedAt() != null ? s.getSubscribedAt().format(ISO_FORMATTER) : null
                ))
                .toList();

        return new SubscriptionPageResponse(items, page, size,
                subscriptionPage.getTotalElements(), subscriptionPage.getTotalPages());
    }

    /**
     * List users subscribed to the current user (my subscribers — private).
     * Only the profile owner should call this.
     */
    public SubscriptionPageResponse listMySubscribers(String tenantId, String targetUserId, int page, int size) {
        Page<Subscription> subscriberPage = subscriptionRepository.findByTenantIdAndTargetUserId(
                tenantId, targetUserId, PageRequest.of(page, size));

        List<SubscriptionUserResponse> items = subscriberPage.getContent().stream()
                .map(s -> new SubscriptionUserResponse(
                        s.getSubscriberId(),
                        s.getSubscriberUsername(),
                        s.getSubscriberDisplayName(),
                        s.getSubscriberAvatarUrl(),
                        s.getSubscribedAt() != null ? s.getSubscribedAt().format(ISO_FORMATTER) : null
                ))
                .toList();

        return new SubscriptionPageResponse(items, page, size,
                subscriberPage.getTotalElements(), subscriberPage.getTotalPages());
    }

    /**
     * Batch check which target user IDs the subscriber is subscribed to.
     */
    public Set<String> findSubscribedTargetIds(String tenantId, String subscriberId, List<String> targetUserIds) {
        return subscriptionRepository.findSubscribedTargetIds(tenantId, subscriberId, targetUserIds);
    }

    // ── Response DTOs ───────────────────────────────────────

    public record SubscriptionUserResponse(
            String userId,
            String username,
            String displayName,
            String avatarUrl,
            String subscribedAt
    ) {}

    public record SubscriptionPageResponse(
            List<SubscriptionUserResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
