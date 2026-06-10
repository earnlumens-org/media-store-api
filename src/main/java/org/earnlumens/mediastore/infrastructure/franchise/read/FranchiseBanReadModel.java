package org.earnlumens.mediastore.infrastructure.franchise.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Read-only projection of a {@code franchise_user_bans} document (owned and
 * written by admin-api). media-store-api reads it only to enforce that a user
 * banned by a franchisor cannot create or operate a franchise under that
 * tenant. {@code tenantId} is the franchisor subdomain.
 */
@Document(collection = "franchise_user_bans")
public class FranchiseBanReadModel {

    @Id
    private String id;

    private String tenantId;
    private String userId;
    private String reason;
    private String bannedBy;
    private Instant bannedAt;

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getReason() { return reason; }
    public String getBannedBy() { return bannedBy; }
    public Instant getBannedAt() { return bannedAt; }
}
