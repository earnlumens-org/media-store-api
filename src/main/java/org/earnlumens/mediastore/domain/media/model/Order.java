package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Order {

    private String id;
    private String tenantId;
    private String userId;
    private String entryId;
    private String sellerId;
    private BigDecimal amountXlm;
    private OrderStatus status;
    private String stellarTxHash;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public Order() {}

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
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getStellarTxHash() { return stellarTxHash; }
    public void setStellarTxHash(String stellarTxHash) { this.stellarTxHash = stellarTxHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
