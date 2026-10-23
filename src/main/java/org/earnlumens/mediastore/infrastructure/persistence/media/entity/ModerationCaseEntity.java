package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One moderation action that blocks content (DATA-MODEL.md §7d).
 * Indexes are created explicitly by {@code ModerationIndexMigration}
 * (auto-index-creation is disabled); the annotations are documentation.
 */
@Document(collection = "moderation_cases")
@CompoundIndex(name = "idx_case_tenant_created", def = "{'tenantId': 1, 'createdAt': -1}")
@CompoundIndex(name = "idx_case_tenant_entries", def = "{'tenantId': 1, 'entryIds': 1}")
@CompoundIndex(name = "idx_case_resolved_created", def = "{'resolvedAt': 1, 'createdAt': 1}")
public class ModerationCaseEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String kind;

    private List<String> entryIds = new ArrayList<>();
    private List<String> collectionIds = new ArrayList<>();

    @NotBlank
    private String reason;

    @NotBlank
    private String actor;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;
    private String resolution;

    public ModerationCaseEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public List<String> getEntryIds() { return entryIds; }
    public void setEntryIds(List<String> entryIds) { this.entryIds = entryIds; }
    public List<String> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<String> collectionIds) { this.collectionIds = collectionIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
}
