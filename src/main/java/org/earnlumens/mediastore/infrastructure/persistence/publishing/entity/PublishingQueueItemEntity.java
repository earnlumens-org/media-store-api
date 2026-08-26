package org.earnlumens.mediastore.infrastructure.persistence.publishing.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Mongo document for a publishing queue item.
 *
 * <p>Indexes are created by {@code PublishingIndexMigration}; the unique
 * partial index on (tenantId, spaceId, entityType, entityId) with
 * status ∈ {QUEUED, LOCKED} enforces "one active item per entity per space"
 * at the storage layer (anti-spam / anti-double-queue).
 */
@Document(collection = "publishing_queue_items")
@CompoundIndex(name = "idx_pubitem_tenant_entity", def = "{'tenantId': 1, 'entityType': 1, 'entityId': 1, 'enqueuedAt': -1}")
@CompoundIndex(name = "idx_pubitem_tenant_block_status", def = "{'tenantId': 1, 'blockId': 1, 'status': 1}")
@CompoundIndex(name = "idx_pubitem_tenant_space_status_seq", def = "{'tenantId': 1, 'spaceId': 1, 'status': 1, 'blockSequence': 1}")
public class PublishingQueueItemEntity {

    @Id
    private String id;

    private String tenantId;
    private String spaceId;
    private String blockId;
    private long blockSequence;
    private LocalDateTime blockPublishAt;
    /** Enum name: ENTRY / COLLECTION / … */
    private String entityType;
    private String entityId;
    private String userId;
    /** Enum name: QUEUED / LOCKED / PUBLISHED / CANCELLED. */
    private String status;
    private boolean fastPass;
    private BigDecimal priorityFeeXlm = BigDecimal.ZERO;
    private LocalDateTime feeLastIncreasedAt;
    private Integer lockedPosition;
    private List<String> appliedOrderIds = new ArrayList<>();
    private LocalDateTime enqueuedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime cancelledAt;

    public PublishingQueueItemEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }

    public long getBlockSequence() { return blockSequence; }
    public void setBlockSequence(long blockSequence) { this.blockSequence = blockSequence; }

    public LocalDateTime getBlockPublishAt() { return blockPublishAt; }
    public void setBlockPublishAt(LocalDateTime blockPublishAt) { this.blockPublishAt = blockPublishAt; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isFastPass() { return fastPass; }
    public void setFastPass(boolean fastPass) { this.fastPass = fastPass; }

    public BigDecimal getPriorityFeeXlm() { return priorityFeeXlm; }
    public void setPriorityFeeXlm(BigDecimal priorityFeeXlm) { this.priorityFeeXlm = priorityFeeXlm; }

    public LocalDateTime getFeeLastIncreasedAt() { return feeLastIncreasedAt; }
    public void setFeeLastIncreasedAt(LocalDateTime feeLastIncreasedAt) { this.feeLastIncreasedAt = feeLastIncreasedAt; }

    public Integer getLockedPosition() { return lockedPosition; }
    public void setLockedPosition(Integer lockedPosition) { this.lockedPosition = lockedPosition; }

    public List<String> getAppliedOrderIds() { return appliedOrderIds; }
    public void setAppliedOrderIds(List<String> appliedOrderIds) {
        this.appliedOrderIds = appliedOrderIds == null ? new ArrayList<>() : appliedOrderIds;
    }

    public LocalDateTime getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(LocalDateTime enqueuedAt) { this.enqueuedAt = enqueuedAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
}
