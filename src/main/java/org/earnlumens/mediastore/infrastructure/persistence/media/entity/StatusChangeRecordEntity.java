package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import java.time.LocalDateTime;

/**
 * Embedded sub-document for status change history in {@link EntryEntity}.
 */
public class StatusChangeRecordEntity {

    private String fromStatus;
    private String toStatus;
    private String actor;
    private String reason;
    private LocalDateTime timestamp;

    public StatusChangeRecordEntity() {}

    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }

    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
