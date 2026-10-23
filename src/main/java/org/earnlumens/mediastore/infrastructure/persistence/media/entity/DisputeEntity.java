package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One purchase affected by a content takedown (DATA-MODEL.md §7e).
 * Indexes are created explicitly by {@code ModerationIndexMigration}; the
 * unique one is the idempotency key of the dispute trigger (D-15).
 */
@Document(collection = "disputes")
@CompoundIndex(name = "idx_dispute_case_entitlement",
        def = "{'tenantId': 1, 'moderationCaseId': 1, 'entitlementId': 1}", unique = true)
@CompoundIndex(name = "idx_dispute_tenant_user_status", def = "{'tenantId': 1, 'userId': 1, 'status': 1}")
@CompoundIndex(name = "idx_dispute_tenant_status_created", def = "{'tenantId': 1, 'status': 1, 'createdAt': -1}")
public class DisputeEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String moderationCaseId;

    @NotBlank
    private String entitlementId;

    private String orderId;

    @NotBlank
    private String userId;

    private String entryId;
    private List<String> affectedEntryIds = new ArrayList<>();
    private String collectionId;
    private String reason;

    @NotBlank
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;

    public DisputeEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getModerationCaseId() { return moderationCaseId; }
    public void setModerationCaseId(String moderationCaseId) { this.moderationCaseId = moderationCaseId; }
    public String getEntitlementId() { return entitlementId; }
    public void setEntitlementId(String entitlementId) { this.entitlementId = entitlementId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public List<String> getAffectedEntryIds() { return affectedEntryIds; }
    public void setAffectedEntryIds(List<String> affectedEntryIds) { this.affectedEntryIds = affectedEntryIds; }
    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) { this.collectionId = collectionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
}
