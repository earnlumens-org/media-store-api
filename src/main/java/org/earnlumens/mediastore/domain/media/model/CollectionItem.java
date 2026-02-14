package org.earnlumens.mediastore.domain.media.model;

public class CollectionItem {

    private String entryId;
    private int position;

    public CollectionItem() {}

    public CollectionItem(String entryId, int position) {
        this.entryId = entryId;
        this.position = position;
    }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
