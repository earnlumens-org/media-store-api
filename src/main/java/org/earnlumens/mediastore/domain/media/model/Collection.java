package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Collection {

    public static final int MAX_ITEMS = 1000;

    private String id;
    private String tenantId;
    private String userId;
    private String authorUsername;
    private String authorAvatarUrl;
    /** Badge key of the author at publish time (e.g. "u1", "u2"). Null if no badge. */
    private String authorBadge;
    private String title;
    private String description;
    private CollectionType collectionType;
    private String coverR2Key;
    /**
     * R2 prefix containing pre-generated cover variants ({@code 320.webp},
     * {@code 640.webp}, {@code 1280.webp}). Set by the thumbnail worker after
     * APPROVE; null when not yet processed, skipped, or failed.
     */
    private String coverVariantsPrefix;
    private CollectionStatus status;
    private MediaVisibility visibility;
    private boolean isPaid;
    private BigDecimal priceXlm;
    private BigDecimal priceUsd;
    private PriceCurrency priceCurrency;
    private String sellerWallet;
    private List<PaymentSplit> paymentSplits = new ArrayList<>();
    private List<CollectionItem> items = new ArrayList<>();
    /** ISO 639-1 language code of the content (e.g. "es", "en"). Nullable for legacy collections. */
    private String contentLanguage;
    private String moderationFeedback;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    public Collection() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }
    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public void setAuthorAvatarUrl(String authorAvatarUrl) { this.authorAvatarUrl = authorAvatarUrl; }
    public String getAuthorBadge() { return authorBadge; }
    public void setAuthorBadge(String authorBadge) { this.authorBadge = authorBadge; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public CollectionType getCollectionType() { return collectionType; }
    public void setCollectionType(CollectionType collectionType) { this.collectionType = collectionType; }
    public String getCoverR2Key() { return coverR2Key; }
    public void setCoverR2Key(String coverR2Key) { this.coverR2Key = coverR2Key; }
    public String getCoverVariantsPrefix() { return coverVariantsPrefix; }
    public void setCoverVariantsPrefix(String coverVariantsPrefix) { this.coverVariantsPrefix = coverVariantsPrefix; }
    public CollectionStatus getStatus() { return status; }
    public void setStatus(CollectionStatus status) { this.status = status; }
    public MediaVisibility getVisibility() { return visibility; }
    public void setVisibility(MediaVisibility visibility) { this.visibility = visibility; }
    public boolean isPaid() { return isPaid; }
    public void setPaid(boolean paid) { isPaid = paid; }
    public BigDecimal getPriceXlm() { return priceXlm; }
    public void setPriceXlm(BigDecimal priceXlm) { this.priceXlm = priceXlm; }
    public BigDecimal getPriceUsd() { return priceUsd; }
    public void setPriceUsd(BigDecimal priceUsd) { this.priceUsd = priceUsd; }
    public PriceCurrency getPriceCurrency() { return priceCurrency; }
    public void setPriceCurrency(PriceCurrency priceCurrency) { this.priceCurrency = priceCurrency; }
    public String getSellerWallet() { return sellerWallet; }
    public void setSellerWallet(String sellerWallet) { this.sellerWallet = sellerWallet; }
    public List<PaymentSplit> getPaymentSplits() { return paymentSplits; }
    public void setPaymentSplits(List<PaymentSplit> paymentSplits) { this.paymentSplits = paymentSplits; }
    public List<CollectionItem> getItems() { return items; }
    public void setItems(List<CollectionItem> items) {
        if (items != null && items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Collection cannot exceed " + MAX_ITEMS + " items");
        }
        this.items = items;
    }
    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }
    public String getModerationFeedback() { return moderationFeedback; }
    public void setModerationFeedback(String moderationFeedback) { this.moderationFeedback = moderationFeedback; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
