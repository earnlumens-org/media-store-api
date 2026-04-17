package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Snapshot of the entry's visible state at the moment of reporting.
 * Lightweight proof of what the reporter actually saw.
 */
public class ReportSnapshot {

    private String title;
    private String description;
    private String thumbnailR2Key;
    private String authorUsername;

    public ReportSnapshot() {}

    public ReportSnapshot(String title, String description, String thumbnailR2Key, String authorUsername) {
        this.title = title;
        this.description = description;
        this.thumbnailR2Key = thumbnailR2Key;
        this.authorUsername = authorUsername;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getThumbnailR2Key() { return thumbnailR2Key; }
    public void setThumbnailR2Key(String thumbnailR2Key) { this.thumbnailR2Key = thumbnailR2Key; }

    public String getAuthorUsername() { return authorUsername; }
    public void setAuthorUsername(String authorUsername) { this.authorUsername = authorUsername; }
}
