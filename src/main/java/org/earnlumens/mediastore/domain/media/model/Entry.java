package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Entry {

    private String id;
    private String tenantId;
    private String userId;
    private String authorUsername;
    private String authorAvatarUrl;
    /** Badge key of the author at publish time (e.g. "u1", "u2"). Null if no badge. */
    private String authorBadge;
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
    /** How this entry can be purchased: INDIVIDUAL, COLLECTION_ONLY, or BOTH. */
    private PricingMode pricingMode;
    /** Stellar public key of the seller at the time of publishing. Required for paid content. */
    private String sellerWallet;
    /**
     * Whether resellers may earn a commission by distributing this entry via a
     * reseller link. Enabled by default for paid content. When false, existing
     * reseller links still open the content but attribute no sale/commission.
     */
    private boolean resellerEnabled = true;
    /**
     * Reseller commission as a percent of the total published price (5–20).
     * Dynamic: read at purchase time, not frozen onto the link, so changing it
     * updates the commission every existing link pays on the next sale. Carved
     * out of the SELLER's own share; the final price is unchanged.
     */
    private BigDecimal resellerCommissionPercent = new BigDecimal("10");
    /**
     * Payment distribution splits for this entry (non-platform only).
     * Currently: SELLER (90%). The PLATFORM split is applied dynamically
     * at payment time from environment config (PLATFORM_WALLET, PLATFORM_FEE_PERCENT).
     * Future: up to 100 recipients (collaborators).
     * Stored as embedded sub-documents in MongoDB.
     */
    private List<PaymentSplit> paymentSplits = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    /**
     * IDs of {@code spaces} (admin-api owned) this entry is published to.
     * Empty when the entry has no explicit destination — it then appears
     * only in feeds that don't filter by space (Explore aggregates
     * everything regardless).
     */
    private List<String> spaceIds = new ArrayList<>();
    /**
     * Per-space publication timestamp, keyed by spaceId. Stamped by the
     * publishing-block scheduler when the entity's block releases; drives the
     * space feed ordering (block order preserved via millisecond offsets).
     * Absent for legacy entries published before the queue existed.
     */
    private Map<String, LocalDateTime> spacePublishedAt = new HashMap<>();
    private String thumbnailR2Key;
    private String previewR2Key;
    /**
     * R2 prefix containing pre-generated thumbnail variants ({@code 320.webp},
     * {@code 640.webp}, {@code 1280.webp}). Set by the thumbnail worker after
     * APPROVE; null when processing has not yet run, was skipped (input below
     * minimum size), or failed (use {@code thumbnailR2Key} as the only source).
     */
    private String thumbnailVariantsPrefix;
    /** R2 prefix for preview-image variants. Same convention as {@link #thumbnailVariantsPrefix}. */
    private String previewVariantsPrefix;
    /** ISO 639-1 language code of the content (e.g. "es", "en"). Nullable for legacy entries. */
    private String contentLanguage;
    private Integer durationSec;
    /** True when HLS transcoding has completed and the master.m3u8 is available on CDN. */
    private boolean hlsReady;
    /** R2 key prefix where HLS segments live (e.g. "private/media/{id}/hls"). */
    private String hlsR2Prefix;
    private long viewCount;

    // ── Original First (automatic content attribution) ──────────────────
    /**
     * Content fingerprint of the FULL asset: SHA-256 over the exact file size
     * plus three 64 KiB samples (head / middle / tail) read server-side from
     * R2 at finalize time. Detects exact re-uploads of the same bytes; a
     * re-encode produces a different fingerprint (documented limitation).
     * Null for entries without a FULL asset (e.g. text-only resources).
     */
    private String contentFingerprint;
    /**
     * True when this entry's FULL asset duplicates another user's earlier
     * upload (same {@link #contentFingerprint}). The entry publishes normally
     * but carries a visible Remix badge and pays the original's royalty.
     */
    private boolean remix;
    /** Canonical original entry (fingerprint-group root). Null when not a remix. */
    private String originalEntryId;
    /** OAuth user id of the canonical original's author (denormalized). */
    private String originalUserId;
    /** Username of the canonical original's author at detection time (denormalized). */
    private String originalAuthorUsername;
    /** When remix status was (re)assigned — upload detection or a granted claim. */
    private LocalDateTime remixDetectedAt;
    /**
     * Royalty the ORIGINAL creator earns on every sale of a remix of THIS
     * entry, as a percent of the total price (5–50, default 20 — can never be
     * zero). Read live from the ORIGINAL entry at purchase time and carved out
     * of the remixer's seller share, exactly like the reseller commission.
     */
    private BigDecimal remixRoyaltyPercent = new BigDecimal("20");
    /**
     * Algorithmic-visibility demotion flag. Set on a user's remix entries when
     * their published catalog is predominantly pure remixes (≥70% with ≥5
     * items). Demoted items rank after everything else in the default Explore
     * ordering but remain fully accessible (never banned or hidden).
     */
    private boolean visibilityDemoted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    /** Human-readable feedback shown to the creator (rejection reason, approval note, etc.). */
    private String moderationFeedback;
    /** Chronological log of every status transition (audit trail). */
    private List<StatusChangeRecord> statusHistory = new ArrayList<>();

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

    public String getAuthorBadge() { return authorBadge; }
    public void setAuthorBadge(String authorBadge) { this.authorBadge = authorBadge; }

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

    public PricingMode getPricingMode() { return pricingMode; }
    public void setPricingMode(PricingMode pricingMode) { this.pricingMode = pricingMode; }

    public String getSellerWallet() { return sellerWallet; }
    public void setSellerWallet(String sellerWallet) { this.sellerWallet = sellerWallet; }

    public boolean isResellerEnabled() { return resellerEnabled; }
    public void setResellerEnabled(boolean resellerEnabled) { this.resellerEnabled = resellerEnabled; }

    public BigDecimal getResellerCommissionPercent() { return resellerCommissionPercent; }
    public void setResellerCommissionPercent(BigDecimal resellerCommissionPercent) { this.resellerCommissionPercent = resellerCommissionPercent; }

    public List<PaymentSplit> getPaymentSplits() { return paymentSplits; }
    public void setPaymentSplits(List<PaymentSplit> paymentSplits) { this.paymentSplits = paymentSplits; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<String> getSpaceIds() { return spaceIds; }
    public void setSpaceIds(List<String> spaceIds) {
        this.spaceIds = spaceIds == null ? new ArrayList<>() : spaceIds;
    }

    public Map<String, LocalDateTime> getSpacePublishedAt() { return spacePublishedAt; }
    public void setSpacePublishedAt(Map<String, LocalDateTime> spacePublishedAt) {
        this.spacePublishedAt = spacePublishedAt == null ? new HashMap<>() : spacePublishedAt;
    }

    public String getThumbnailR2Key() { return thumbnailR2Key; }
    public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }

    public String getPreviewR2Key() { return previewR2Key; }
    public void setPreviewR2Key(String previewR2Key) { this.previewR2Key = previewR2Key; }

    public String getThumbnailVariantsPrefix() { return thumbnailVariantsPrefix; }
    public void setThumbnailVariantsPrefix(String thumbnailVariantsPrefix) { this.thumbnailVariantsPrefix = thumbnailVariantsPrefix; }

    public String getPreviewVariantsPrefix() { return previewVariantsPrefix; }
    public void setPreviewVariantsPrefix(String previewVariantsPrefix) { this.previewVariantsPrefix = previewVariantsPrefix; }

    public String getContentLanguage() { return contentLanguage; }
    public void setContentLanguage(String contentLanguage) { this.contentLanguage = contentLanguage; }

    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }

    public boolean isHlsReady() { return hlsReady; }
    public void setHlsReady(boolean hlsReady) { this.hlsReady = hlsReady; }

    public String getHlsR2Prefix() { return hlsR2Prefix; }
    public void setHlsR2Prefix(String hlsR2Prefix) { this.hlsR2Prefix = hlsR2Prefix; }

    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }

    public String getContentFingerprint() { return contentFingerprint; }
    public void setContentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; }

    public boolean isRemix() { return remix; }
    public void setRemix(boolean remix) { this.remix = remix; }

    public String getOriginalEntryId() { return originalEntryId; }
    public void setOriginalEntryId(String originalEntryId) { this.originalEntryId = originalEntryId; }

    public String getOriginalUserId() { return originalUserId; }
    public void setOriginalUserId(String originalUserId) { this.originalUserId = originalUserId; }

    public String getOriginalAuthorUsername() { return originalAuthorUsername; }
    public void setOriginalAuthorUsername(String originalAuthorUsername) { this.originalAuthorUsername = originalAuthorUsername; }

    public LocalDateTime getRemixDetectedAt() { return remixDetectedAt; }
    public void setRemixDetectedAt(LocalDateTime remixDetectedAt) { this.remixDetectedAt = remixDetectedAt; }

    public BigDecimal getRemixRoyaltyPercent() { return remixRoyaltyPercent; }
    public void setRemixRoyaltyPercent(BigDecimal remixRoyaltyPercent) { this.remixRoyaltyPercent = remixRoyaltyPercent; }

    public boolean isVisibilityDemoted() { return visibilityDemoted; }
    public void setVisibilityDemoted(boolean visibilityDemoted) { this.visibilityDemoted = visibilityDemoted; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public String getModerationFeedback() { return moderationFeedback; }
    public void setModerationFeedback(String moderationFeedback) { this.moderationFeedback = moderationFeedback; }

    public List<StatusChangeRecord> getStatusHistory() {
        if (statusHistory == null) {
            statusHistory = new ArrayList<>();
        } else if (!(statusHistory instanceof ArrayList)) {
            statusHistory = new ArrayList<>(statusHistory);
        }
        return statusHistory;
    }
    public void setStatusHistory(List<StatusChangeRecord> statusHistory) { this.statusHistory = statusHistory; }
}
