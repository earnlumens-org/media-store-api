package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Persistence shape of {@code EntryVersion} (embedded in entries.versions[]). */
public class EntryVersionEmbeddable {
    private int version;
    private String assetId;
    private String hlsR2Prefix;
    private String title;
    private String description;
    private String resourceContent;
    private List<String> tags;
    private String thumbnailR2Key;
    private String previewR2Key;
    private LocalDateTime approvedAt;
    private List<String> changeSummary = new ArrayList<>();
    private String creatorNote;

    public EntryVersionEmbeddable() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getHlsR2Prefix() { return hlsR2Prefix; }
    public void setHlsR2Prefix(String hlsR2Prefix) { this.hlsR2Prefix = hlsR2Prefix; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getResourceContent() { return resourceContent; }
    public void setResourceContent(String resourceContent) { this.resourceContent = resourceContent; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getThumbnailR2Key() { return thumbnailR2Key; }
    public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }
    public String getPreviewR2Key() { return previewR2Key; }
    public void setPreviewR2Key(String previewR2Key) { this.previewR2Key = previewR2Key; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public List<String> getChangeSummary() { return changeSummary; }
    public void setChangeSummary(List<String> changeSummary) { this.changeSummary = changeSummary; }
    public String getCreatorNote() { return creatorNote; }
    public void setCreatorNote(String creatorNote) { this.creatorNote = creatorNote; }
}
