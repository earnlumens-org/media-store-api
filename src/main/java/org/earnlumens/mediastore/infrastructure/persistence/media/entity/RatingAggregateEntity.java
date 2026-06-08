package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Denormalized rating aggregate, one document per (tenant, entry).
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

    /** Total number of ratings (all proof types). */
    private long count;

    /** Sum of all stars (all proof types) — used to derive the average. */
    private long sum;

    /** Star histogram: number of 1★, 2★, 3★, 4★, 5★ ratings. */
    private long star1;
    private long star2;
    private long star3;
    private long star4;
    private long star5;

    /** Number of ratings backed by a verified purchase. */
    private long verifiedCount;

    /** Sum of stars from verified-purchase ratings only. */
    private long verifiedSum;

    /**
     * Bayesian-weighted score (0&ndash;5) for ranking. Pulls low-N entries
     * toward a neutral prior so {@code 5.0 (1)} cannot outrank {@code 4.6 (300)}.
     */
    private double bayesianScore;

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

    public long getSum() { return sum; }
    public void setSum(long sum) { this.sum = sum; }

    public long getStar1() { return star1; }
    public void setStar1(long star1) { this.star1 = star1; }

    public long getStar2() { return star2; }
    public void setStar2(long star2) { this.star2 = star2; }

    public long getStar3() { return star3; }
    public void setStar3(long star3) { this.star3 = star3; }

    public long getStar4() { return star4; }
    public void setStar4(long star4) { this.star4 = star4; }

    public long getStar5() { return star5; }
    public void setStar5(long star5) { this.star5 = star5; }

    public long getVerifiedCount() { return verifiedCount; }
    public void setVerifiedCount(long verifiedCount) { this.verifiedCount = verifiedCount; }

    public long getVerifiedSum() { return verifiedSum; }
    public void setVerifiedSum(long verifiedSum) { this.verifiedSum = verifiedSum; }

    public double getBayesianScore() { return bayesianScore; }
    public void setBayesianScore(double bayesianScore) { this.bayesianScore = bayesianScore; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
