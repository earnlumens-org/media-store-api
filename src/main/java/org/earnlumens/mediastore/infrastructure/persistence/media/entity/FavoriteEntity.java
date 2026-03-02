package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for the {@code favorites} collection.
 *
 * <p>Indexes:
 * <ul>
 *   <li><b>idx_tenant_user_item</b> — unique constraint: a user can favorite an item only once.</li>
 *   <li><b>idx_tenant_user_added</b> — paginated list sorted by newest first.</li>
 * </ul>
 */
@Document(collection = "favorites")
@CompoundIndex(name = "idx_tenant_user_item", def = "{'tenantId': 1, 'userId': 1, 'itemId': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_user_added", def = "{'tenantId': 1, 'userId': 1, 'addedAt': -1}")
public class FavoriteEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String itemId;

    @NotBlank
    private String itemType;

    @CreatedDate
    private LocalDateTime addedAt;

    public FavoriteEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}
