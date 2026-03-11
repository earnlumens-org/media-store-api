package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Entry {

    private String id;
    private String tenantId;
    private String userId;
    private String authorUsername;
    private String authorAvatarUrl;
    private String title;
    private String description;
    /** Rich text body for RESOURCE entries (articles, notes). Optional. */
    private String resourceContent;
    private EntryType type;
    private EntryStatus status;
    /** Status before archiving — used to restore on unarchive. */
    private EntryStatus previousStatus;
    private MediaVisibility visibility;
    private boolean isPaid;
    private BigDecimal priceXlm;
    private BigDecimal priceUsd;
    private PriceCurrency priceCurrency;
    /** Stellar public key of the seller at the time of publishing. Required for paid content. */
    private String sellerWallet;
    /**
     * Payment distribution splits for this entry.
     * Currently: PLATFORM (10%) + SELLER (90%).
     * Future: up to 100 recipients (collaborators).
     * Stored as embedded sub-documents in MongoDB.
     */
    private List<PaymentSplit> paymentSplits = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private String thumbnailR2Key;
    private String previewR2Key;
    /** ISO 639-1 language code of the content (e.g. "es", "en"). Nullable for legacy entries. */
    private String contentLanguage;
    private Integer durationSec;
    private long viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    public Entry() {}

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

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getResourceContent() { return resourceContent; }
    public void setResourceContent(String resourceContent) { this.resourceContent = resourceContent; }

    public EntryType getType() { return type; }
    public void setType(EntryType type) { this.type = type; }

    public EntryStatus getStatus() { return status; }
    public void setStatus(EntryStatus status) { this.status = status; }

    public EntryStatus getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(EntryStatus previousStatus) { this.previousStatus = previousStatus; }

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

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getThumbnailR2Key() { return thumbnailR2Key; }
    public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }

    public String getPreviewR2Key() { return previewR2Key; }
    public void setPreviewR2Key(String previewR2Key) { this.previewR2Key = previewR2Key; }

    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }

    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }

    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
