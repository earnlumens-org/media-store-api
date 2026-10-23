package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Single staging slot for an edit of an already-published entry that is
 * awaiting moderation (MYCELIUM-RESILIENCE.md N9, DATA-MODEL.md §2.3).
 *
 * <p>The live fields of the {@link Entry} are never touched while the edit
 * is reviewed: the entry stays {@code PUBLISHED}, buyers and visitors keep
 * seeing the approved content. Approval projects the slot onto the entry
 * (and records the previous state as a version, D-14); rejection discards
 * the slot and leaves everything visible exactly as it was (D-13
 * {@code CHANGES_REJECTED}). A newer edit overwrites the slot and cancels the
 * previous moderation job — there is no queue of pending edits.
 *
 * <p>Null fields mean "unchanged from the live entry"; the moderation job is
 * always built from the merged view (slot over live), so the classifier sees
 * exactly what would be published.
 */
public class PendingVersion {

    private String title;
    private String description;
    private String resourceContent;
    private List<String> tags;
    private String thumbnailR2Key;
    private String previewR2Key;
    /** Reserved for the versioned FULL replacement of H8/N8; unused by N9. */
    private String assetId;
    /** Reserved for the versioned FULL replacement of H8/N8; unused by N9. */
    private String hlsR2Prefix;
    /** Parts touched by this edit: TITLE, DESCRIPTION, THUMBNAIL, PREVIEW, RESOURCE_TEXT, TAGS. */
    private List<String> changeSummary = new ArrayList<>();
    private String creatorNote;
    private LocalDateTime submittedAt;
    /** Active moderation job reviewing this slot (null once it completed). */
    private String moderationJobId;

    public PendingVersion() {}

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
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getHlsR2Prefix() { return hlsR2Prefix; }
    public void setHlsR2Prefix(String hlsR2Prefix) { this.hlsR2Prefix = hlsR2Prefix; }
    public List<String> getChangeSummary() {
        if (changeSummary == null) changeSummary = new ArrayList<>();
        else if (!(changeSummary instanceof ArrayList)) changeSummary = new ArrayList<>(changeSummary);
        return changeSummary;
    }
    public void setChangeSummary(List<String> changeSummary) { this.changeSummary = changeSummary; }
    public String getCreatorNote() { return creatorNote; }
    public void setCreatorNote(String creatorNote) { this.creatorNote = creatorNote; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getModerationJobId() { return moderationJobId; }
    public void setModerationJobId(String moderationJobId) { this.moderationJobId = moderationJobId; }
}
