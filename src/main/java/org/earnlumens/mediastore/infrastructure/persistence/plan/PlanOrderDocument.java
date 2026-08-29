package org.earnlumens.mediastore.infrastructure.persistence.plan;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A prepaid Pro-plan purchase (custom-domain-upgrade 1B.1). Lives in its own
 * {@code plan_orders} collection — NOT in {@code orders} — because the
 * write-ownership convention differs: media-store-api writes the payment
 * lifecycle fields here, while admin-api (the only writer of {@code tenants})
 * reads this collection to apply the purchase and stamps {@code appliedAt}.
 *
 * <p>Status semantics reuse {@code OrderStatus}
 * (PENDING → PROCESSING → COMPLETED / FAILED / EXPIRED).
 */
@Document(collection = "plan_orders")
@CompoundIndex(name = "idx_plan_order_tenant_status", def = "{'tenantId': 1, 'status': 1}")
@CompoundIndex(name = "idx_plan_order_stellar_tx", def = "{'stellarTxHash': 1}")
@CompoundIndex(name = "idx_plan_order_applied", def = "{'status': 1, 'appliedAt': 1, 'completedAt': 1}")
public class PlanOrderDocument {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    /** OAuth user-id of the tenant OWNER (the only identity allowed to buy the plan). */
    @NotBlank
    private String ownerOauthUserId;

    /** "MONTHLY" | "YEARLY". */
    @NotBlank
    private String period;

    private BigDecimal amountUsd;
    private BigDecimal amountXlm;
    private BigDecimal xlmUsdRate;

    /** OrderStatus name: PENDING | PROCESSING | COMPLETED | FAILED | EXPIRED. */
    @NotBlank
    private String status;

    private String stellarTxHash;

    // ── Payment flow fields (same two-phase pipeline as OrderEntity) ──
    private String buyerWallet;
    private String memo;
    private String unsignedXdr;
    private String signedXdr;
    private String integrityHash;
    private LocalDateTime expiresAt;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    /**
     * Stamped by admin-api (atomic CAS) when the plan extension was applied
     * to the tenant. Null on a COMPLETED order means the callback was lost —
     * admin-api's backup sweep will pick it up.
     */
    private Instant appliedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getOwnerOauthUserId() { return ownerOauthUserId; }
    public void setOwnerOauthUserId(String ownerOauthUserId) { this.ownerOauthUserId = ownerOauthUserId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public void setAmountUsd(BigDecimal amountUsd) { this.amountUsd = amountUsd; }
    public BigDecimal getAmountXlm() { return amountXlm; }
    public void setAmountXlm(BigDecimal amountXlm) { this.amountXlm = amountXlm; }
    public BigDecimal getXlmUsdRate() { return xlmUsdRate; }
    public void setXlmUsdRate(BigDecimal xlmUsdRate) { this.xlmUsdRate = xlmUsdRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStellarTxHash() { return stellarTxHash; }
    public void setStellarTxHash(String stellarTxHash) { this.stellarTxHash = stellarTxHash; }
    public String getBuyerWallet() { return buyerWallet; }
    public void setBuyerWallet(String buyerWallet) { this.buyerWallet = buyerWallet; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public String getUnsignedXdr() { return unsignedXdr; }
    public void setUnsignedXdr(String unsignedXdr) { this.unsignedXdr = unsignedXdr; }
    public String getSignedXdr() { return signedXdr; }
    public void setSignedXdr(String signedXdr) { this.signedXdr = signedXdr; }
    public String getIntegrityHash() { return integrityHash; }
    public void setIntegrityHash(String integrityHash) { this.integrityHash = integrityHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
