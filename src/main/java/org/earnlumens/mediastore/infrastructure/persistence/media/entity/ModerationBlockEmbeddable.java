package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import java.time.LocalDateTime;

/** Persistence shape of {@code ModerationBlock} (embedded in entries / collections). */
public class ModerationBlockEmbeddable {
    private String caseId;
    private String reason;
    private String by;
    private LocalDateTime at;

    public ModerationBlockEmbeddable() {}

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getBy() { return by; }
    public void setBy(String by) { this.by = by; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
