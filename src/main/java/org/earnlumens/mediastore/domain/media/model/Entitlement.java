package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

public class Entitlement {

    private String id;
    private String tenantId;
    private String userId;
    private String entryId;
    private EntitlementStatus status;
    private LocalDateTime grantedAt;

    public Entitlement() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public EntitlementStatus getStatus() {
        return status;
    }

    public void setStatus(EntitlementStatus status) {
        this.status = status;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }
}
