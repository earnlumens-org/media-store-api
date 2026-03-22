package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "entitlements")
@CompoundIndex(name = "idx_ent_tenant_user_target_entry", def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'entryId': 1}", unique = true)
@CompoundIndex(name = "idx_ent_tenant_user_target_coll", def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'collectionId': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_entry_status", def = "{'tenantId': 1, 'entryId': 1, 'status': 1}")
@CompoundIndex(name = "idx_ent_tenant_coll_status", def = "{'tenantId': 1, 'collectionId': 1, 'status': 1}")
@CompoundIndex(name = "idx_tenant_user_status_granted", def = "{'tenantId': 1, 'userId': 1, 'status': 1, 'grantedAt': -1}")
public class EntitlementEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String targetType;

    private String entryId;

    private String collectionId;

    private String grantType;

    private String orderId;

    @NotBlank
    private String status;

    @CreatedDate
    private LocalDateTime grantedAt;

    private LocalDateTime expiresAt;

    public EntitlementEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) { this.collectionId = collectionId; }

    public String getGrantType() { return grantType; }
    public void setGrantType(String grantType) { this.grantType = grantType; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
