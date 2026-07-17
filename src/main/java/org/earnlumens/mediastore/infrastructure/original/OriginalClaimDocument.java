package org.earnlumens.mediastore.infrastructure.original;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Audit record of an Original First ownership claim ("Claim as Original").
 *
 * <p>One document per (tenant, fingerprint, claimant): a user gets exactly one
 * claim per fingerprint group, whatever the outcome, which kills claim-spam and
 * makes every decision fully auditable and transparent (the score breakdown is
 * persisted). Uniqueness is enforced by {@code idx_claim_unique}, created by
 * {@code OriginalFirstIndexMigration} (auto-index-creation is disabled).
 */
@Document(collection = "original_claims")
@CompoundIndex(name = "idx_claim_unique", def = "{'tenantId': 1, 'fingerprint': 1, 'claimantUserId': 1}", unique = true)
@CompoundIndex(name = "idx_claim_user_created", def = "{'tenantId': 1, 'claimantUserId': 1, 'createdAt': -1}")
public class OriginalClaimDocument {

    @Id
    private String id;

    private String tenantId;
    /** Content fingerprint identifying the disputed group. */
    private String fingerprint;
    /** OAuth user id of the claimant. */
    private String claimantUserId;
    /** Entry the claimant owns inside the group (their evidence). */
    private String claimantEntryId;
    /** OAuth user id of the credit holder at claim time. */
    private String holderUserId;
    /** Canonical original entry at claim time. */
    private String holderEntryId;
    /** GRANTED or REJECTED. */
    private String outcome;
    /** Claimant's total score (upload priority + creator trust + seniority). */
    private double claimantScore;
    /** Holder's total score. */
    private double holderScore;
    /** Human-readable score breakdown, persisted for transparency. */
    private String scoreBreakdown;

    @CreatedDate
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getClaimantUserId() { return claimantUserId; }
    public void setClaimantUserId(String claimantUserId) { this.claimantUserId = claimantUserId; }

    public String getClaimantEntryId() { return claimantEntryId; }
    public void setClaimantEntryId(String claimantEntryId) { this.claimantEntryId = claimantEntryId; }

    public String getHolderUserId() { return holderUserId; }
    public void setHolderUserId(String holderUserId) { this.holderUserId = holderUserId; }

    public String getHolderEntryId() { return holderEntryId; }
    public void setHolderEntryId(String holderEntryId) { this.holderEntryId = holderEntryId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public double getClaimantScore() { return claimantScore; }
    public void setClaimantScore(double claimantScore) { this.claimantScore = claimantScore; }

    public double getHolderScore() { return holderScore; }
    public void setHolderScore(double holderScore) { this.holderScore = holderScore; }

    public String getScoreBreakdown() { return scoreBreakdown; }
    public void setScoreBreakdown(String scoreBreakdown) { this.scoreBreakdown = scoreBreakdown; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
