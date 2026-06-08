package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Domain model for a single user rating (1&ndash;5 stars + optional review)
 * of a <em>target</em> &mdash; an entry or a whole collection.
 *
 * <p>Tenant-scoped: every rating belongs to exactly one tenant. The
 * {@link #proofType} is the immutable anti-fraud spine that survives
 * public&harr;paid transitions; it is never downgraded once set.</p>
 */
public class Rating {

    private String id;
    private String tenantId;
    private TargetType targetType;
    private String targetId;
    private String userId;
    private String username;
    private String creatorUserId;
    private int stars;
    private String comment;
    private RatingProofType proofType;
    private String proofRef;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Rating() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public TargetType getTargetType() { return targetType; }
    public void setTargetType(TargetType targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(String creatorUserId) { this.creatorUserId = creatorUserId; }

    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public RatingProofType getProofType() { return proofType; }
    public void setProofType(RatingProofType proofType) { this.proofType = proofType; }

    public String getProofRef() { return proofRef; }
    public void setProofRef(String proofRef) { this.proofRef = proofRef; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
