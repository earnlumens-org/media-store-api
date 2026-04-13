package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Immutable record of a status transition in an entry's lifecycle.
 * The {@code actor} field uses a generic label (e.g. "EarnLumens") to avoid
 * revealing whether the decision was made by a bot or a human.
 */
public class StatusChangeRecord {

    private EntryStatus fromStatus;
    private EntryStatus toStatus;
    private String actor;
    private String reason;
    private LocalDateTime timestamp;

    public StatusChangeRecord() {}

    public StatusChangeRecord(EntryStatus fromStatus, EntryStatus toStatus, String actor, String reason) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.reason = reason;
        this.timestamp = LocalDateTime.now();
    }

    public EntryStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(EntryStatus fromStatus) { this.fromStatus = fromStatus; }

    public EntryStatus getToStatus() { return toStatus; }
    public void setToStatus(EntryStatus toStatus) { this.toStatus = toStatus; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
