package org.earnlumens.mediastore.infrastructure.franchise.write;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Write-side mapping of the {@code franchises} collection used by the
 * franchisee self-service flow in media-store-api (create / edit own
 * franchise). The collection is shared with admin-api (which owns franchisor
 * governance); both apps map the SAME document by identical field names so the
 * documents round-trip across services without a converter.
 *
 * <p>media-store-api only ever writes the fields a franchise <i>owner</i> is
 * allowed to set (branding + payout + the frozen commission snapshot). It never
 * writes governance fields such as {@code disabledReason} — those stay the
 * exclusive concern of admin-api's franchisor tooling.
 *
 * <p>{@code tenantId} is the franchisor's <b>subdomain</b> (the canonical
 * tenantId used throughout media-store-api), so every query is tenant-scoped.
 *
 * @see org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel
 */
@Document(collection = "franchises")
public class FranchiseWriteModel {

    @Id
    private String id;

    /** Franchisor subdomain (canonical tenantId). Immutable after creation. */
    private String tenantId;

    /** URL slug under {@code /f/<slug>}. Immutable after creation. */
    private String slug;

    /** OAuth provider user-id of the franchise owner. Immutable. */
    private String ownerOauthUserId;
    private String ownerUsername;
    private String ownerDisplayName;

    /** Commission frozen from the franchisor default at creation. Immutable. */
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

    /** Set by admin-api governance only; mapped here so it is never clobbered. */
    private String disabledReason;
    private String disabledBy;
    private Instant disabledAt;

    private Instant acceptedTermsAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public FranchiseWriteModel() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getOwnerOauthUserId() { return ownerOauthUserId; }
    public void setOwnerOauthUserId(String ownerOauthUserId) { this.ownerOauthUserId = ownerOauthUserId; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    public String getOwnerDisplayName() { return ownerDisplayName; }
    public void setOwnerDisplayName(String ownerDisplayName) { this.ownerDisplayName = ownerDisplayName; }

    public BigDecimal getCommissionPercent() { return commissionPercent; }
    public void setCommissionPercent(BigDecimal commissionPercent) { this.commissionPercent = commissionPercent; }

    public String getPayoutWallet() { return payoutWallet; }
    public void setPayoutWallet(String payoutWallet) { this.payoutWallet = payoutWallet; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLogoR2Key() { return logoR2Key; }
    public void setLogoR2Key(String logoR2Key) { this.logoR2Key = logoR2Key; }

    public String getCoverR2Key() { return coverR2Key; }
    public void setCoverR2Key(String coverR2Key) { this.coverR2Key = coverR2Key; }

    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDisabledReason() { return disabledReason; }
    public void setDisabledReason(String disabledReason) { this.disabledReason = disabledReason; }

    public String getDisabledBy() { return disabledBy; }
    public void setDisabledBy(String disabledBy) { this.disabledBy = disabledBy; }

    public Instant getDisabledAt() { return disabledAt; }
    public void setDisabledAt(Instant disabledAt) { this.disabledAt = disabledAt; }

    public Instant getAcceptedTermsAt() { return acceptedTermsAt; }
    public void setAcceptedTermsAt(Instant acceptedTermsAt) { this.acceptedTermsAt = acceptedTermsAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() { return "ACTIVE".equals(status); }
}
