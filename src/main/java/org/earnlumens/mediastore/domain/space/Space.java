package org.earnlumens.mediastore.domain.space;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only projection of an admin-api {@code spaces} document.
 *
 * <p>{@code admin-api} owns the write side of this collection;
 * {@code media-store-api} only reads it (validation when publishing,
 * sidebar listing, public space feed).
 *
 * <p>Tenant isolation is enforced at the repository layer: every read
 * goes through {@code findByTenantIdAnd…} so a space from tenant A can
 * never be observed by tenant B's request even if {@code spaceId} were
 * forged.
 */
public class Space {

    private String id;
    private String tenantId;
    private String key;
    private boolean systemSpace;
    private SpaceStatus status = SpaceStatus.ACTIVE;
    private boolean showInSidebar = true;
    private boolean allowPublishing = true;
    private int sortOrder;
    private String icon;
    private SpacePublishRule whoCanPublish = SpacePublishRule.ALL;
    private String baseName;
    /** Locale code → translated name. May be empty for the system space. */
    private Map<String, String> translations = new HashMap<>();
    private Instant archivedAt;

    // ── Publishing Block configuration (admin-api owned, nullable → defaults) ──
    /** Base slots per publishing block. Null → default 48. */
    private Integer publishingBlockSize;
    /** Minutes between block publications. Null → default 10. */
    private Integer publishingBlockIntervalMinutes;
    /** FastPass price in USD (converted to XLM at payment time). Null → default 2. */
    private BigDecimal fastPassPriceUsd;

    /** Default base slots per publishing block. */
    public static final int DEFAULT_BLOCK_SIZE = 48;
    /** Default minutes between block publications. */
    public static final int DEFAULT_BLOCK_INTERVAL_MINUTES = 10;
    /** Default FastPass price in USD. */
    public static final BigDecimal DEFAULT_FAST_PASS_PRICE_USD = new BigDecimal("2");

    public Space() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public boolean isSystemSpace() { return systemSpace; }
    public void setSystemSpace(boolean systemSpace) { this.systemSpace = systemSpace; }

    public SpaceStatus getStatus() { return status; }
    public void setStatus(SpaceStatus status) { this.status = status; }

    public boolean isShowInSidebar() { return showInSidebar; }
    public void setShowInSidebar(boolean showInSidebar) { this.showInSidebar = showInSidebar; }

    public boolean isAllowPublishing() { return allowPublishing; }
    public void setAllowPublishing(boolean allowPublishing) { this.allowPublishing = allowPublishing; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public SpacePublishRule getWhoCanPublish() { return whoCanPublish; }
    public void setWhoCanPublish(SpacePublishRule whoCanPublish) { this.whoCanPublish = whoCanPublish; }

    public String getBaseName() { return baseName; }
    public void setBaseName(String baseName) { this.baseName = baseName; }

    public Map<String, String> getTranslations() { return translations; }
    public void setTranslations(Map<String, String> translations) {
        this.translations = translations == null ? new HashMap<>() : translations;
    }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    public Integer getPublishingBlockSize() { return publishingBlockSize; }
    public void setPublishingBlockSize(Integer publishingBlockSize) { this.publishingBlockSize = publishingBlockSize; }

    public Integer getPublishingBlockIntervalMinutes() { return publishingBlockIntervalMinutes; }
    public void setPublishingBlockIntervalMinutes(Integer publishingBlockIntervalMinutes) { this.publishingBlockIntervalMinutes = publishingBlockIntervalMinutes; }

    public BigDecimal getFastPassPriceUsd() { return fastPassPriceUsd; }
    public void setFastPassPriceUsd(BigDecimal fastPassPriceUsd) { this.fastPassPriceUsd = fastPassPriceUsd; }

    /** Block size with default applied (invalid/absent config → 48). */
    public int effectiveBlockSize() {
        return publishingBlockSize != null && publishingBlockSize > 0
                ? publishingBlockSize : DEFAULT_BLOCK_SIZE;
    }

    /** Block interval in minutes with default applied (invalid/absent config → 10). */
    public int effectiveBlockIntervalMinutes() {
        return publishingBlockIntervalMinutes != null && publishingBlockIntervalMinutes > 0
                ? publishingBlockIntervalMinutes : DEFAULT_BLOCK_INTERVAL_MINUTES;
    }

    /** FastPass price in USD with default applied (invalid/absent config → 2). */
    public BigDecimal effectiveFastPassPriceUsd() {
        return fastPassPriceUsd != null && fastPassPriceUsd.signum() > 0
                ? fastPassPriceUsd : DEFAULT_FAST_PASS_PRICE_USD;
    }
}
