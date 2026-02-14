package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

public class CollectionItemEmbeddable {

    private String entryId;
    private int position;

    public CollectionItemEmbeddable() {}

    public CollectionItemEmbeddable(String entryId, int position) {
        this.entryId = entryId;
        this.position = position;
    }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
