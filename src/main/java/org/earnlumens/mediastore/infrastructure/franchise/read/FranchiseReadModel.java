package org.earnlumens.mediastore.infrastructure.franchise.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

/**
 * Read-only projection of a {@code franchises} document (owned and written by
 * admin-api). media-store-api never mutates franchises — it only reads them to
 * resolve a franchise storefront ({@code <subdomain>.earnlumens.org/f/<slug>}),
 * apply the franchise commission split at purchase time, and render franchise
 * branding.
 *
 * <p>{@code tenantId} here is the franchisor's <b>subdomain</b>, matching the
 * canonical {@code tenantId} used throughout media-store-api, so a franchise is
 * looked up with a single {@code (tenantId, slug)} query.
 */
@Document(collection = "franchises")
public class FranchiseReadModel {

    @Id
    private String id;

    /** Franchisor subdomain (canonical tenantId). */
    private String tenantId;

    private String slug;

    private String ownerOauthUserId;
    private String ownerUsername;
    private String ownerDisplayName;

    /** Frozen commission as a percentage of the franchisor's own profit share. */
    private BigDecimal commissionPercent;

    /** Stellar public key where the franchise receives its commission. */
    private String payoutWallet;

    // In-app branding override (null => inherit franchisor branding).
    private String title;
    private String description;
    private String logoR2Key;
    private String coverR2Key;
    private String accentColor;

    /** "ACTIVE" | "DISABLED". */
    private String status;

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSlug() { return slug; }
    public String getOwnerOauthUserId() { return ownerOauthUserId; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getOwnerDisplayName() { return ownerDisplayName; }
    public BigDecimal getCommissionPercent() { return commissionPercent; }
    public String getPayoutWallet() { return payoutWallet; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getLogoR2Key() { return logoR2Key; }
    public String getCoverR2Key() { return coverR2Key; }
    public String getAccentColor() { return accentColor; }
    public String getStatus() { return status; }

    public boolean isActive() { return "ACTIVE".equals(status); }
}
