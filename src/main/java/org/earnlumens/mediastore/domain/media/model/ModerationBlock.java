package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

/**
 * Explicit moderation block on an entry or collection (MYCELIUM-RESILIENCE.md
 * H3 / D-01 / S-03, DATA-MODEL.md §8.2).
 *
 * <p>{@code != null} means <b>blocked for everyone</b> — buyers, visitors and
 * the creator alike, in every version (D-16). {@code == null} means access is
 * decided by {@code status} alone and buyers always retain what they bought.
 * It is set only by an explicit moderation action (takedown or preventive
 * block) and cleared on reinstatement; it is never a side effect of a status
 * change made by the creator.
 */
public class ModerationBlock {

    /** {@code moderation_cases._id} this block belongs to. */
    private String caseId;
    private String reason;
    /** Moderator id or {@code "EarnLumens"} for automatic blocks. */
    private String by;
    private LocalDateTime at;

    public ModerationBlock() {}

    public ModerationBlock(String caseId, String reason, String by, LocalDateTime at) {
        this.caseId = caseId;
        this.reason = reason;
        this.by = by;
        this.at = at;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getBy() { return by; }
    public void setBy(String by) { this.by = by; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
