package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * One membership record of an entry in a collection (DATA-MODEL.md §4.1,
 * MYCELIUM-RESILIENCE.md H2 / D-20 / S-04). Records are never deleted:
 * removing an item stamps {@code removedAt} (and clears {@code position});
 * re-adding appends a new record. {@code addedAt == null} on historical
 * data means "since forever" — no backfill.
 */
public class CollectionItem {

    private String entryId;
    /** 0-based sort order among active records; {@code null} once removed. */
    private Integer position;
    private LocalDateTime addedAt;
    private LocalDateTime removedAt;

    public CollectionItem() {}

    public CollectionItem(String entryId, int position) {
        this.entryId = entryId;
        this.position = position;
    }

    public CollectionItem(String entryId, int position, LocalDateTime addedAt) {
        this(entryId, position);
        this.addedAt = addedAt;
    }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
    public LocalDateTime getRemovedAt() { return removedAt; }
    public void setRemovedAt(LocalDateTime removedAt) { this.removedAt = removedAt; }

    /** Still listed in the collection. */
    public boolean isActive() { return removedAt == null; }

    /** {@link #getPosition()} as a primitive for sorting (removed records sort last). */
    public int positionOrMax() { return position == null ? Integer.MAX_VALUE : position; }

    /**
     * Union-by-right-date rule (D-20): a right that started at {@code grantedAt}
     * covers this record while it is active or if it was removed at or after
     * the right started ({@code >=} favours the buyer, R-32). A {@code null}
     * {@code grantedAt} (historical entitlement) is treated as "since forever".
     */
    public boolean coversRightSince(LocalDateTime grantedAt) {
        return removedAt == null || grantedAt == null || !removedAt.isBefore(grantedAt);
    }

    /**
     * Instant from which a right granted at {@code grantedAt} reached this
     * record: {@code max(grantedAt, addedAt)}; an absent {@code addedAt}
     * means the item was there before the right started (S-07).
     */
    public LocalDateTime rightStartFor(LocalDateTime grantedAt) {
        if (addedAt == null) return grantedAt;
        if (grantedAt == null) return addedAt;
        return addedAt.isAfter(grantedAt) ? addedAt : grantedAt;
    }
}
