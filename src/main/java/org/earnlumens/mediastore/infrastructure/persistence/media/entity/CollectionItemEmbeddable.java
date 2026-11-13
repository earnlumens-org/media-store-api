package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import java.time.LocalDateTime;

public class CollectionItemEmbeddable {

    private String entryId;
    private Integer position;
    private LocalDateTime addedAt;
    private LocalDateTime removedAt;

    public CollectionItemEmbeddable() {}

    public CollectionItemEmbeddable(String entryId, Integer position) {
        this.entryId = entryId;
        this.position = position;
    }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }

    public LocalDateTime getRemovedAt() { return removedAt; }
    public void setRemovedAt(LocalDateTime removedAt) { this.removedAt = removedAt; }
}
