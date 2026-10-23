package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One <b>approved</b> version of an entry (MYCELIUM-RESILIENCE.md D-14 / N8,
 * DATA-MODEL.md §2.3). Metadata only: it points at {@code Asset} documents
 * that already exist; no file is ever copied.
 *
 * <p>Written by the N9 approval path so that the content a buyer acquired is
 * never lost when an edit is approved. The buyer-side choice of version
 * ({@code version_choices}, N8) is not part of this class.
 */
public class EntryVersion {

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

    public EntryVersion() {}

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
