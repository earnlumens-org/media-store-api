package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One document per <b>purchase affected</b> by a content takedown
 * (MYCELIUM-RESILIENCE.md H3 / D-09 / D-15, DATA-MODEL.md §7e).
 *
 * <p>Informational: the system opens it in {@link DisputeStatus#OPEN} when a
 * {@link ModerationCase} blocks content a buyer has a right to; only an
 * administrator resolves it. The entitlement is never revoked by the dispute.
 * Idempotent on {@code (tenantId, moderationCaseId, entitlementId)}.
 */
public class Dispute {

    private String id;
    private String tenantId;
    private String moderationCaseId;
    private String entitlementId;
    private String orderId;
    private String userId;
    /** Entry that triggered this dispute (the smallest affected id); null for a collection-only block. */
    private String entryId;
    /** Every entry of the case this purchase gives access to. */
    private List<String> affectedEntryIds = new ArrayList<>();
    /** Set when the access path is a collection entitlement. */
    private String collectionId;
    private String reason;
    private DisputeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;

    public Dispute() {}

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
    public List<String> getAffectedEntryIds() {
        if (affectedEntryIds == null) affectedEntryIds = new ArrayList<>();
        return affectedEntryIds;
    }
    public void setAffectedEntryIds(List<String> affectedEntryIds) { this.affectedEntryIds = affectedEntryIds; }
    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) { this.collectionId = collectionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public DisputeStatus getStatus() { return status; }
    public void setStatus(DisputeStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
}
