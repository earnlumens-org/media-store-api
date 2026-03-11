package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Order {

    private String id;
    private String tenantId;
    private String userId;
    private String entryId;
    private String sellerId;
    private BigDecimal amountXlm;
    /** Original USD amount (only set for USD-priced entries) */
    private BigDecimal originalAmountUsd;
    /** XLM/USD rate used for conversion (only set for USD-priced entries) */
    private BigDecimal xlmUsdRate;
    /** Price currency of the entry at order time: "XLM" or "USD" */
    private String priceCurrency;
    private OrderStatus status;
    private String stellarTxHash;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    // ── Payment flow fields ──
    /** Buyer's Stellar public key (G...) */
    private String buyerWallet;
    /** MEMO text attached to the Stellar tx (e.g. "TOTAL: 5.00 XLM") */
    private String memo;
    /** Base-64 XDR of the unsigned transaction envelope, built by the backend */
    private String unsignedXdr;
    /** Base-64 XDR of the signed transaction envelope, returned after user signs */
    private String signedXdr;
    /** SHA-256 hex of the unsigned XDR — used to verify the user didn't tamper with the tx */
    private String integrityHash;
    /** When this order expires (timeBounds maxTime) */
    private LocalDateTime expiresAt;
    /** Snapshot of the payment splits at order-creation time (amounts, not just percents) */
    private List<PaymentSplit> paymentSplits = new ArrayList<>();

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
    public BigDecimal getOriginalAmountUsd() { return originalAmountUsd; }
    public void setOriginalAmountUsd(BigDecimal originalAmountUsd) { this.originalAmountUsd = originalAmountUsd; }
    public BigDecimal getXlmUsdRate() { return xlmUsdRate; }
    public void setXlmUsdRate(BigDecimal xlmUsdRate) { this.xlmUsdRate = xlmUsdRate; }
    public String getPriceCurrency() { return priceCurrency; }
    public void setPriceCurrency(String priceCurrency) { this.priceCurrency = priceCurrency; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getStellarTxHash() { return stellarTxHash; }
    public void setStellarTxHash(String stellarTxHash) { this.stellarTxHash = stellarTxHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

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
    public List<PaymentSplit> getPaymentSplits() { return paymentSplits; }
    public void setPaymentSplits(List<PaymentSplit> paymentSplits) { this.paymentSplits = paymentSplits; }
}
