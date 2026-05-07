package org.earnlumens.mediastore.infrastructure.persistence.space.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Mongo projection of a {@code spaces} document. The collection is owned and
 * written exclusively by {@code admin-api}; this side is read-only.
 */
@Document(collection = "spaces")
public class SpaceEntity {

    @Id
    private String id;

    private String tenantId;

    private String key;

    private boolean systemSpace;

    /** Stored as enum name. Values: {@code ACTIVE}, {@code ARCHIVED}. */
    private String status;

    private boolean showInSidebar;

    private boolean allowPublishing;

    private int sortOrder;

    private String icon;

    /** Stored as enum name. Values: {@code ALL}, {@code VERIFIED_BLUE}, {@code VERIFIED_GOLD}. */
    private String whoCanPublish;

    private String baseName;

    private Map<String, String> translations = new HashMap<>();

    private Instant archivedAt;

    public SpaceEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public boolean isSystemSpace() { return systemSpace; }
    public void setSystemSpace(boolean systemSpace) { this.systemSpace = systemSpace; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isShowInSidebar() { return showInSidebar; }
    public void setShowInSidebar(boolean showInSidebar) { this.showInSidebar = showInSidebar; }

    public boolean isAllowPublishing() { return allowPublishing; }
    public void setAllowPublishing(boolean allowPublishing) { this.allowPublishing = allowPublishing; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getWhoCanPublish() { return whoCanPublish; }
    public void setWhoCanPublish(String whoCanPublish) { this.whoCanPublish = whoCanPublish; }

    public String getBaseName() { return baseName; }
    public void setBaseName(String baseName) { this.baseName = baseName; }

    public Map<String, String> getTranslations() { return translations; }
    public void setTranslations(Map<String, String> translations) {
        this.translations = translations == null ? new HashMap<>() : translations;
    }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
}
