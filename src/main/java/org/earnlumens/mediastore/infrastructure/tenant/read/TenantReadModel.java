package org.earnlumens.mediastore.infrastructure.tenant.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

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

    private BigDecimal platformFeePercent;
    private BigDecimal tenantFeePercent;

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

    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal platformFeePercent) { this.platformFeePercent = platformFeePercent; }

    public BigDecimal getTenantFeePercent() { return tenantFeePercent; }
    public void setTenantFeePercent(BigDecimal tenantFeePercent) { this.tenantFeePercent = tenantFeePercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean isBlocked() { return "BLOCKED".equals(status); }
}
