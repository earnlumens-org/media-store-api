package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for the {@code ratings} collection — one like/dislike
 * vote and optional review comment per user per <em>target</em>
 * (an entry or a collection).
 *
 * <p>Indexes:
 * <ul>
 *   <li><b>idx_tenant_user_target</b> — <b>unique</b>: a user can rate a target
 *       only once. This single constraint kills ballot-stuffing from one
 *       account; multiple ratings can only happen with distinct accounts.</li>
 *   <li><b>idx_tenant_target_created</b> — paginated reviews for a target,
 *       newest first.</li>
 *   <li><b>idx_tenant_user_created</b> — rate-limit window: a user's recent
 *       ratings.</li>
 *   <li><b>idx_tenant_target_liked</b> — fast like/dislike tally recomputation.</li>
 * </ul>
 */
@Document(collection = "ratings")
@CompoundIndex(name = "idx_tenant_user_target", def = "{'tenantId': 1, 'userId': 1, 'targetType': 1, 'targetId': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_target_created", def = "{'tenantId': 1, 'targetType': 1, 'targetId': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_tenant_user_created", def = "{'tenantId': 1, 'userId': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_tenant_target_liked", def = "{'tenantId': 1, 'targetType': 1, 'targetId': 1, 'liked': 1}")
public class RatingEntity {

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

    @NotBlank
    private String userId;

    /** Denormalized for review display. */
    private String username;

    /** Owner of the rated target (denormalized for self-rating checks / audits). */
    @NotBlank
    private String creatorUserId;

    /** {@code true} = like (thumbs up), {@code false} = dislike (thumbs down). */
    private boolean liked;

    /** Optional plain-text review. Sanitized (no markup) before persistence. */
    @Size(max = 1000)
    private String comment;

    /**
     * Immutable anti-fraud proof: {@code PURCHASE} or {@code FREE_VIEW}.
     * Stored as the enum name. Never downgraded once set.
     */
    @NotBlank
    private String proofType;

    /**
     * Audit reference for the proof — e.g. {@code "ENTRY:<id>"} or
     * {@code "COLLECTION:<id>"} identifying the entitlement that granted access.
     */
    private String proofRef;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public RatingEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(String creatorUserId) { this.creatorUserId = creatorUserId; }

    public boolean isLiked() { return liked; }
    public void setLiked(boolean liked) { this.liked = liked; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getProofType() { return proofType; }
    public void setProofType(String proofType) { this.proofType = proofType; }

    public String getProofRef() { return proofRef; }
    public void setProofRef(String proofRef) { this.proofRef = proofRef; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
