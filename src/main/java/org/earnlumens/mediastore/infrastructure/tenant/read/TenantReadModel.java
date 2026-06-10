package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-side projection of the {@code tenants} collection owned and written by admin-api.
 * <p>
 * media-store-api never mutates this document — it only reads the fee schedule and
 * tenant wallet so it can build three-way payment splits and enforce access rules.
 * <p>
 * Field names mirror {@code admin-api}'s {@code Tenant} entity 1:1 so the same
 * Mongo documents deserialize into this class without a converter.
 */
@Document(collection = "tenants")
public class TenantReadModel {

    @Id
    private String id;

    private String ownerOauthUserId;
    private String ownerUsername;
    private String subdomain;
    private String tenantWallet;

    /** Optional storefront app-bar label, mirrors admin-api's Tenant.brandText. */
    private String brandText;

    /** Optional storefront title, mirrors admin-api's Tenant.title (display fallback). */
    private String title;

    /** Optional R2 object key for the storefront logo. Lives under public/tenants/{subdomain}/logo/. */
    private String logoR2Key;

    /** Optional dark-theme storefront logo. Falls back to {@link #logoR2Key} when null. */
    private String logoR2KeyDark;

    /** Optional R2 object key for the per-tenant browser favicon. Lives under public/tenants/{subdomain}/favicon/. */
    private String faviconR2Key;

    /**
     * When {@code true} the storefront renders no text label next to the
     * logo at all (logo-only mode). Persisted alongside {@link #brandText}
     * so flipping the switch back off restores the saved label.
     */
    private boolean brandTextHidden;

    /** Optional browser-tab title; null means "use tenant title (or hardcoded EARNLUMENS)". */
    private String browserTitle;

    // ---- Storefront hero banner (per-tenant marketing block) -----------
    // Mirrors admin-api's Tenant fields 1:1. media-store-api only reads.

    private boolean bannerEnabled;
    private String bannerImageR2Key;
    private String bannerEyebrow;
    private String bannerHeadline;
    private String bannerSubheadline;
    private String bannerCtaLabel;
    private String bannerCtaUrl;
    private String bannerImageAlt;

    /** Per-tenant default Vuetify theme keys. Mirror admin-api's Tenant fields. */
    private String defaultLightTheme;
    private String defaultDarkTheme;

    /**
     * Per-tenant uploads kill switch. Boxed so a missing Mongo field
     * deserialises to {@code null} and the safe default ({@code true} —
     * uploads enabled) applies through {@link #isUploadsEnabled()}.
     */
    private Boolean uploadsEnabled;

    /**
     * Optional allowlist of entry types the tenant accepts in new uploads.
     * Mirrors {@code admin-api}'s {@code Tenant.allowedEntryTypes}. {@code null}
     * or empty = no restriction (every type allowed) so legacy tenants stay
     * unrestricted without a migration. Entries are uppercase {@code EntryType}
     * names (VIDEO / AUDIO / IMAGE / RESOURCE / COLLECTION).
     */
    private List<String> allowedEntryTypes;

    private BigDecimal platformFeePercent;
    private BigDecimal tenantFeePercent;

    /**
     * Franchise model opt-in. Boxed so a missing Mongo field deserialises to
     * {@code null} and the safe default ({@code false} — franchises off) applies
     * through {@link #isFranchisesEnabled()}. Mirrors admin-api's Tenant field.
     */
    private Boolean franchisesEnabled;

    /** When {@code true}, new franchise sign-ups are paused (model stays on). */
    private Boolean franchisesPaused;

    /**
     * Default commission (percentage of the franchisor's own profit share)
     * snapshotted onto each new franchise at creation. Mirrors admin-api's
     * Tenant field; read here so media-store-api can freeze it onto a franchise.
     */
    private BigDecimal defaultFranchiseCommissionPercent;

    private String status;          // "ACTIVE" | "BLOCKED"
    private String blockedReason;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerOauthUserId() { return ownerOauthUserId; }
    public void setOwnerOauthUserId(String ownerOauthUserId) { this.ownerOauthUserId = ownerOauthUserId; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    public String getSubdomain() { return subdomain; }
    public void setSubdomain(String subdomain) { this.subdomain = subdomain; }

    public String getTenantWallet() { return tenantWallet; }
    public void setTenantWallet(String tenantWallet) { this.tenantWallet = tenantWallet; }

    public String getBrandText() { return brandText; }
    public void setBrandText(String brandText) { this.brandText = brandText; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getLogoR2Key() { return logoR2Key; }
    public void setLogoR2Key(String logoR2Key) { this.logoR2Key = logoR2Key; }

    public String getLogoR2KeyDark() { return logoR2KeyDark; }
    public void setLogoR2KeyDark(String logoR2KeyDark) { this.logoR2KeyDark = logoR2KeyDark; }

    public String getFaviconR2Key() { return faviconR2Key; }
    public void setFaviconR2Key(String faviconR2Key) { this.faviconR2Key = faviconR2Key; }

    public boolean isBrandTextHidden() { return brandTextHidden; }
    public void setBrandTextHidden(boolean brandTextHidden) { this.brandTextHidden = brandTextHidden; }

    public String getBrowserTitle() { return browserTitle; }
    public void setBrowserTitle(String browserTitle) { this.browserTitle = browserTitle; }

    public boolean isBannerEnabled() { return bannerEnabled; }
    public void setBannerEnabled(boolean bannerEnabled) { this.bannerEnabled = bannerEnabled; }

    public String getBannerImageR2Key() { return bannerImageR2Key; }
    public void setBannerImageR2Key(String bannerImageR2Key) { this.bannerImageR2Key = bannerImageR2Key; }

    public String getBannerEyebrow() { return bannerEyebrow; }
    public void setBannerEyebrow(String bannerEyebrow) { this.bannerEyebrow = bannerEyebrow; }

    public String getBannerHeadline() { return bannerHeadline; }
    public void setBannerHeadline(String bannerHeadline) { this.bannerHeadline = bannerHeadline; }

    public String getBannerSubheadline() { return bannerSubheadline; }
    public void setBannerSubheadline(String bannerSubheadline) { this.bannerSubheadline = bannerSubheadline; }

    public String getBannerCtaLabel() { return bannerCtaLabel; }
    public void setBannerCtaLabel(String bannerCtaLabel) { this.bannerCtaLabel = bannerCtaLabel; }

    public String getBannerCtaUrl() { return bannerCtaUrl; }
    public void setBannerCtaUrl(String bannerCtaUrl) { this.bannerCtaUrl = bannerCtaUrl; }

    public String getBannerImageAlt() { return bannerImageAlt; }
    public void setBannerImageAlt(String bannerImageAlt) { this.bannerImageAlt = bannerImageAlt; }

    public String getDefaultLightTheme() { return defaultLightTheme; }
    public void setDefaultLightTheme(String defaultLightTheme) { this.defaultLightTheme = defaultLightTheme; }

    public String getDefaultDarkTheme() { return defaultDarkTheme; }
    public void setDefaultDarkTheme(String defaultDarkTheme) { this.defaultDarkTheme = defaultDarkTheme; }

    /** Treats a missing/null field as enabled (legacy tenants stay on by default). */
    public boolean isUploadsEnabled() { return uploadsEnabled == null || uploadsEnabled; }
    public Boolean getUploadsEnabled() { return uploadsEnabled; }
    public void setUploadsEnabled(Boolean uploadsEnabled) { this.uploadsEnabled = uploadsEnabled; }

    public List<String> getAllowedEntryTypes() { return allowedEntryTypes; }
    public void setAllowedEntryTypes(List<String> allowedEntryTypes) { this.allowedEntryTypes = allowedEntryTypes; }

    /**
     * Returns {@code true} when {@code type} (uppercase canonical enum name
     * like {@code VIDEO}) is allowed for this tenant. A null/empty allowlist
     * is treated as "all allowed" so the gate is backward-compatible.
     */
    public boolean isEntryTypeAllowed(String type) {
        if (allowedEntryTypes == null || allowedEntryTypes.isEmpty()) return true;
        if (type == null) return false;
        return allowedEntryTypes.contains(type);
    }

    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal platformFeePercent) { this.platformFeePercent = platformFeePercent; }

    public BigDecimal getTenantFeePercent() { return tenantFeePercent; }
    public void setTenantFeePercent(BigDecimal tenantFeePercent) { this.tenantFeePercent = tenantFeePercent; }

    /** Treats a missing/null field as disabled (franchises are opt-in). */
    public boolean isFranchisesEnabled() { return franchisesEnabled != null && franchisesEnabled; }
    public Boolean getFranchisesEnabled() { return franchisesEnabled; }
    public void setFranchisesEnabled(Boolean franchisesEnabled) { this.franchisesEnabled = franchisesEnabled; }

    public boolean isFranchisesPaused() { return franchisesPaused != null && franchisesPaused; }
    public Boolean getFranchisesPaused() { return franchisesPaused; }
    public void setFranchisesPaused(Boolean franchisesPaused) { this.franchisesPaused = franchisesPaused; }

    public BigDecimal getDefaultFranchiseCommissionPercent() { return defaultFranchiseCommissionPercent; }
    public void setDefaultFranchiseCommissionPercent(BigDecimal defaultFranchiseCommissionPercent) { this.defaultFranchiseCommissionPercent = defaultFranchiseCommissionPercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean isBlocked() { return "BLOCKED".equals(status); }
}
