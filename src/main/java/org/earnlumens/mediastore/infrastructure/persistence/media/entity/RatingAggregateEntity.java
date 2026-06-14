package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Denormalized like/dislike aggregate, one document per (tenant, target).
 * Recomputed from the {@code ratings} collection on every write so it can
 * never drift from the source of truth.
 *
 * <p>The aggregate keeps the <b>verified (PURCHASE-only)</b> totals separate
 * from the overall totals. This is what makes the public&harr;paid transition
 * fraud-resistant: a paid entry can always surface a score built solely from
 * real buyers, regardless of any {@code FREE_VIEW} ratings collected while it
 * was free.</p>
 */
@Document(collection = "rating_aggregates")
@CompoundIndex(name = "idx_tenant_target", def = "{'tenantId': 1, 'targetType': 1, 'targetId': 1}", unique = true)
public class RatingAggregateEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    /** {@code ENTRY} or {@code COLLECTION} (enum name). */
    @NotBlank
    private String targetType;

    /** ID of the rated entry or collection. */
    @NotBlank
    private String targetId;

    /** Total number of votes (all proof types) — {@code likes + dislikes}. */
    private long count;

    /** Number of likes (thumbs up), all proof types. */
    private long likes;

    /** Number of dislikes (thumbs down), all proof types. */
    private long dislikes;

    /** Number of votes backed by a verified purchase. */
    private long verifiedCount;

    /** Number of likes from verified-purchase votes only. */
    private long verifiedLikes;

    /** Number of dislikes from verified-purchase votes only. */
    private long verifiedDislikes;

    private LocalDateTime updatedAt;

    public RatingAggregateEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

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
