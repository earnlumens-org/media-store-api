package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Collection {

    public static final int MAX_ITEMS = 1000;

    private String id;
    private String tenantId;
    private String userId;
    private String title;
    private String description;
    private CollectionType collectionType;
    private String coverR2Key;
    private CollectionStatus status;
    private List<CollectionItem> items = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    public Collection() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public CollectionType getCollectionType() { return collectionType; }
    public void setCollectionType(CollectionType collectionType) { this.collectionType = collectionType; }
    public String getCoverR2Key() { return coverR2Key; }
    public void setCoverR2Key(String coverR2Key) { this.coverR2Key = coverR2Key; }
    public CollectionStatus getStatus() { return status; }
    public void setStatus(CollectionStatus status) { this.status = status; }
    public List<CollectionItem> getItems() { return items; }
    public void setItems(List<CollectionItem> items) {
        if (items != null && items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Collection cannot exceed " + MAX_ITEMS + " items");
        }
        this.items = items;
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
