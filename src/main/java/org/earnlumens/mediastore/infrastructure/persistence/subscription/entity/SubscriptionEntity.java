package org.earnlumens.mediastore.infrastructure.persistence.subscription.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for user subscriptions.
 *
 * <p>Indexes:
 * <ul>
 *   <li>{tenantId, subscriberId, targetUserId} UNIQUE — prevents duplicate subscriptions</li>
 *   <li>{tenantId, subscriberId, subscribedAt DESC} — "my subscriptions" list</li>
 *   <li>{tenantId, targetUserId, subscribedAt DESC} — "my subscribers" list + count</li>
 * </ul>
 * </p>
 */
@Document(collection = "subscriptions")
@CompoundIndex(name = "idx_tenant_sub_target", def = "{'tenantId': 1, 'subscriberId': 1, 'targetUserId': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_sub_date", def = "{'tenantId': 1, 'subscriberId': 1, 'subscribedAt': -1}")
@CompoundIndex(name = "idx_tenant_target_date", def = "{'tenantId': 1, 'targetUserId': 1, 'subscribedAt': -1}")
public class SubscriptionEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String subscriberId;

    private String subscriberUsername;
    private String subscriberDisplayName;
    private String subscriberAvatarUrl;

    @NotBlank
    private String targetUserId;

    private String targetUsername;
    private String targetDisplayName;
    private String targetAvatarUrl;

    @CreatedDate
    private LocalDateTime subscribedAt;

    public SubscriptionEntity() {}

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
