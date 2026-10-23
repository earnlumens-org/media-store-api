package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One document per <b>moderation action that blocks content</b>
 * (MYCELIUM-RESILIENCE.md H3 / D-15, DATA-MODEL.md §7d).
 *
 * <p>Its id is the {@code moderationCaseId}: the grouping key the
 * administrator resolves in bulk, the value stored in
 * {@code Entry.moderationBlock.caseId} and the parent of every
 * {@link Dispute}. One case may block several entries at once (e.g. three
 * items of a collection removed in one action).
 */
public class ModerationCase {

    private String id;
    private String tenantId;
    private ModerationCaseKind kind;
    private List<String> entryIds = new ArrayList<>();
    private List<String> collectionIds = new ArrayList<>();
    private String reason;
    /** Moderator id or {@code "EarnLumens"} (automatic). */
    private String actor;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private ModerationCaseResolution resolution;

    public ModerationCase() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public ModerationCaseKind getKind() { return kind; }
    public void setKind(ModerationCaseKind kind) { this.kind = kind; }
    public List<String> getEntryIds() {
        if (entryIds == null) entryIds = new ArrayList<>();
        return entryIds;
    }
    public void setEntryIds(List<String> entryIds) { this.entryIds = entryIds; }
    public List<String> getCollectionIds() {
        if (collectionIds == null) collectionIds = new ArrayList<>();
        return collectionIds;
    }
    public void setCollectionIds(List<String> collectionIds) { this.collectionIds = collectionIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public ModerationCaseResolution getResolution() { return resolution; }
    public void setResolution(ModerationCaseResolution resolution) { this.resolution = resolution; }
}
