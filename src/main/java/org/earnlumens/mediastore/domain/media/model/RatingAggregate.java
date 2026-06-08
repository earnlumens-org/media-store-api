package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Domain model for the denormalized rating aggregate of a target
 * (entry or collection). Always recomputed from the source ratings, so
 * it never drifts. Verified-purchase totals are kept separate so a
 * public&harr;paid flip cannot inflate the buyer score.
 *
 * <p>Tenant-scoped: one aggregate per {@code (tenantId, targetType, targetId)}.</p>
 */
public class RatingAggregate {

    private String id;
    private String tenantId;
    private TargetType targetType;
    private String targetId;
    private long count;
    private long sum;
    private long star1;
    private long star2;
    private long star3;
    private long star4;
    private long star5;
    private long verifiedCount;
    private long verifiedSum;
    private double bayesianScore;
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
