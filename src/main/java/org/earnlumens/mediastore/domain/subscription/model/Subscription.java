package org.earnlumens.mediastore.domain.subscription.model;

import java.time.LocalDateTime;

/**
 * Domain model representing a subscription relationship between two users.
 * A subscriber follows a target user (creator).
 *
 * <p>Denormalized user fields allow efficient list rendering without
 * extra lookups, following patterns used by large-scale social platforms.</p>
 */
public class Subscription {

    private String id;
    private String tenantId;

    /** The user who subscribes (follower). */
    private String subscriberId;
    private String subscriberUsername;
    private String subscriberDisplayName;
    private String subscriberAvatarUrl;

    /** The user being subscribed to (creator/target). */
    private String targetUserId;
    private String targetUsername;
    private String targetDisplayName;
    private String targetAvatarUrl;

    private LocalDateTime subscribedAt;

    public Subscription() {}

    // ── Getters & Setters ───────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }

    public String getSubscriberUsername() { return subscriberUsername; }
    public void setSubscriberUsername(String subscriberUsername) { this.subscriberUsername = subscriberUsername; }

    public String getSubscriberDisplayName() { return subscriberDisplayName; }
    public void setSubscriberDisplayName(String subscriberDisplayName) { this.subscriberDisplayName = subscriberDisplayName; }

    public String getSubscriberAvatarUrl() { return subscriberAvatarUrl; }
    public void setSubscriberAvatarUrl(String subscriberAvatarUrl) { this.subscriberAvatarUrl = subscriberAvatarUrl; }

    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }

    public String getTargetDisplayName() { return targetDisplayName; }
    public void setTargetDisplayName(String targetDisplayName) { this.targetDisplayName = targetDisplayName; }

    public String getTargetAvatarUrl() { return targetAvatarUrl; }
    public void setTargetAvatarUrl(String targetAvatarUrl) { this.targetAvatarUrl = targetAvatarUrl; }

    public LocalDateTime getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(LocalDateTime subscribedAt) { this.subscribedAt = subscribedAt; }
}
