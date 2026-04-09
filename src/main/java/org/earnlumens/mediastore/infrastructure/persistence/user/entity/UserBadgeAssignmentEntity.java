package org.earnlumens.mediastore.infrastructure.persistence.user.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "user_badge_assignments")
@CompoundIndex(name = "idx_badge_tenant_user_status", def = "{'tenantId': 1, 'userId': 1, 'status': 1}")
@CompoundIndex(name = "idx_badge_tenant_type_status", def = "{'tenantId': 1, 'badgeType': 1, 'status': 1}")
@CompoundIndex(name = "idx_badge_tenant_status_expires", def = "{'tenantId': 1, 'status': 1, 'expiresAt': 1}")
public class UserBadgeAssignmentEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String badgeType;

    @NotBlank
    private String status;

    @NotBlank
    private String assignedBy;

    private String assignedByUserId;

    private LocalDateTime startedAt;

    private LocalDateTime expiresAt;

    @CreatedDate
    private LocalDateTime createdAt;

    public UserBadgeAssignmentEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getBadgeType() { return badgeType; }
    public void setBadgeType(String badgeType) { this.badgeType = badgeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }

    public String getAssignedByUserId() { return assignedByUserId; }
    public void setAssignedByUserId(String assignedByUserId) { this.assignedByUserId = assignedByUserId; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
