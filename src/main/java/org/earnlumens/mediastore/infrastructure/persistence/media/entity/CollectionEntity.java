package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "collections")
@CompoundIndex(name = "idx_coll_tenant_id", def = "{'tenantId': 1, '_id': 1}", unique = true)
@CompoundIndex(name = "idx_coll_tenant_user", def = "{'tenantId': 1, 'userId': 1}")
@CompoundIndex(name = "idx_coll_tenant_status_published", def = "{'tenantId': 1, 'status': 1, 'publishedAt': -1}")
@CompoundIndex(name = "idx_coll_tenant_items_entry", def = "{'tenantId': 1, 'items.entryId': 1}")
public class CollectionEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 2000)
    private String description;

    @NotBlank
    private String collectionType;

    private String coverR2Key;

    @NotBlank
    private String status;

    private List<CollectionItemEmbeddable> items = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime publishedAt;

    public CollectionEntity() {}

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

    public String getCollectionType() { return collectionType; }
    public void setCollectionType(String collectionType) { this.collectionType = collectionType; }

    public String getCoverR2Key() { return coverR2Key; }
    public void setCoverR2Key(String coverR2Key) { this.coverR2Key = coverR2Key; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<CollectionItemEmbeddable> getItems() { return items; }
    public void setItems(List<CollectionItemEmbeddable> items) { this.items = items; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
