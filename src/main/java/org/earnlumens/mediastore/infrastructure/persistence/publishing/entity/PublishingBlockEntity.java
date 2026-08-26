package org.earnlumens.mediastore.infrastructure.persistence.publishing.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Mongo document for a Space's publishing block.
 *
 * <p>Indexes are created by {@code PublishingIndexMigration} (auto-index
 * creation is disabled platform-wide); the annotations below are
 * documentation of the intended shape.
 */
@Document(collection = "publishing_blocks")
@CompoundIndex(name = "idx_pubblock_tenant_space_seq", def = "{'tenantId': 1, 'spaceId': 1, 'sequence': -1}", unique = true)
@CompoundIndex(name = "idx_pubblock_tenant_space_status_seq", def = "{'tenantId': 1, 'spaceId': 1, 'status': 1, 'sequence': 1}")
@CompoundIndex(name = "idx_pubblock_status_lock", def = "{'status': 1, 'lockAt': 1}")
@CompoundIndex(name = "idx_pubblock_status_publish", def = "{'status': 1, 'publishAt': 1}")
public class PublishingBlockEntity {

    @Id
    private String id;

    private String tenantId;
    private String spaceId;
    private long sequence;
    /** Enum name: OPEN / LOCKED / PUBLISHED. */
    private String status;
    private int baseCapacity;
    private int baseSlotsUsed;
    private int fastPassSlots;
    private LocalDateTime lockAt;
    private LocalDateTime publishAt;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    public PublishingBlockEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSpaceId() { return spaceId; }
    public void setSpaceId(String spaceId) { this.spaceId = spaceId; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getBaseCapacity() { return baseCapacity; }
    public void setBaseCapacity(int baseCapacity) { this.baseCapacity = baseCapacity; }

    public int getBaseSlotsUsed() { return baseSlotsUsed; }
    public void setBaseSlotsUsed(int baseSlotsUsed) { this.baseSlotsUsed = baseSlotsUsed; }

    public int getFastPassSlots() { return fastPassSlots; }
    public void setFastPassSlots(int fastPassSlots) { this.fastPassSlots = fastPassSlots; }

    public LocalDateTime getLockAt() { return lockAt; }
    public void setLockAt(LocalDateTime lockAt) { this.lockAt = lockAt; }

    public LocalDateTime getPublishAt() { return publishAt; }
    public void setPublishAt(LocalDateTime publishAt) { this.publishAt = publishAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
