package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Document(collection = "orders")
@CompoundIndex(name = "idx_order_tenant_user_entry", def = "{'tenantId': 1, 'userId': 1, 'entryId': 1}", unique = true)
@CompoundIndex(name = "idx_order_tenant_entry_status", def = "{'tenantId': 1, 'entryId': 1, 'status': 1}")
@CompoundIndex(name = "idx_order_tenant_user_status_created", def = "{'tenantId': 1, 'userId': 1, 'status': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_order_tenant_seller_status_created", def = "{'tenantId': 1, 'sellerId': 1, 'status': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_order_stellar_tx", def = "{'stellarTxHash': 1}")
public class OrderEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String entryId;

    @NotBlank
    private String sellerId;

    private BigDecimal amountXlm;

    @NotBlank
    private String status;

    private String stellarTxHash;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public OrderEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }

    public BigDecimal getAmountXlm() { return amountXlm; }
    public void setAmountXlm(BigDecimal amountXlm) { this.amountXlm = amountXlm; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStellarTxHash() { return stellarTxHash; }
    public void setStellarTxHash(String stellarTxHash) { this.stellarTxHash = stellarTxHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
