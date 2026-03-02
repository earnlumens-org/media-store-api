package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * A saved/bookmarked item (entry or collection) by a user.
 * Analogous to YouTube "Save to playlist" or Patreon bookmarks.
 *
 * <p>Unlike entitlements, favorites carry no access semantics —
 * they are purely a user-curated list of items the user wants
 * quick access to across all devices.</p>
 */
public class Favorite {

    private String id;
    private String tenantId;
    private String userId;
    private String itemId;
    private FavoriteItemType itemType;
    private LocalDateTime addedAt;

    public Favorite() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public FavoriteItemType getItemType() { return itemType; }
    public void setItemType(FavoriteItemType itemType) { this.itemType = itemType; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}
