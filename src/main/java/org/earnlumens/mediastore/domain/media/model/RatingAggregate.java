package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Domain model for the denormalized like/dislike aggregate of a target
 * (entry or collection). Always recomputed from the source ratings, so
 * it never drifts. Verified-purchase totals are kept separate so a
 * public&harr;paid flip cannot inflate the buyer score.
 *
 * <p>Roblox-style: the headline number is the percentage of likes
 * ({@code likes / (likes + dislikes)}). Tenant-scoped: one aggregate per
 * {@code (tenantId, targetType, targetId)}.</p>
 */
public class RatingAggregate {

    private String id;
    private String tenantId;
    private TargetType targetType;
    private String targetId;
    private long count;
    private long likes;
    private long dislikes;
    private long verifiedCount;
    private long verifiedLikes;
    private long verifiedDislikes;
    private LocalDateTime updatedAt;

    public RatingAggregate() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public TargetType getTargetType() { return targetType; }
    public void setTargetType(TargetType targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }

    public long getLikes() { return likes; }
    public void setLikes(long likes) { this.likes = likes; }

    public long getDislikes() { return dislikes; }
    public void setDislikes(long dislikes) { this.dislikes = dislikes; }

    public long getVerifiedCount() { return verifiedCount; }
    public void setVerifiedCount(long verifiedCount) { this.verifiedCount = verifiedCount; }

    public long getVerifiedLikes() { return verifiedLikes; }
    public void setVerifiedLikes(long verifiedLikes) { this.verifiedLikes = verifiedLikes; }

    public long getVerifiedDislikes() { return verifiedDislikes; }
    public void setVerifiedDislikes(long verifiedDislikes) { this.verifiedDislikes = verifiedDislikes; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
