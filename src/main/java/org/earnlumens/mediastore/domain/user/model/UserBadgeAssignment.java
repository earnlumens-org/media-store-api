package org.earnlumens.mediastore.domain.user.model;

import java.time.LocalDateTime;

/**
 * Represents a badge assignment to a user with optional expiration.
 * A user may hold multiple badge assignments (e.g. community + ecosystem).
 */
public class UserBadgeAssignment {

    private String id;
    private String tenantId;
    private String userId;
    private BadgeType badgeType;
    private BadgeAssignmentStatus status;
    private BadgeAssignedBy assignedBy;
    private String assignedByUserId;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public UserBadgeAssignment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public BadgeType getBadgeType() { return badgeType; }
    public void setBadgeType(BadgeType badgeType) { this.badgeType = badgeType; }

    public BadgeAssignmentStatus getStatus() { return status; }
    public void setStatus(BadgeAssignmentStatus status) { this.status = status; }

    public BadgeAssignedBy getAssignedBy() { return assignedBy; }
    public void setAssignedBy(BadgeAssignedBy assignedBy) { this.assignedBy = assignedBy; }

    public String getAssignedByUserId() { return assignedByUserId; }
    public void setAssignedByUserId(String assignedByUserId) { this.assignedByUserId = assignedByUserId; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isActive() {
        return status == BadgeAssignmentStatus.ACTIVE;
    }

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
