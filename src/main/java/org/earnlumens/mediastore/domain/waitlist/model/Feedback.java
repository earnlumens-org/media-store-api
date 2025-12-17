package org.earnlumens.mediastore.domain.waitlist.model;

import java.time.LocalDateTime;

public class Feedback {

    private String id;
    private String userId;
    private String feedback;
    private LocalDateTime entryDate;

    public Feedback() {
    }

    public Feedback(String userId, String feedback) {
        this.userId = userId;
        this.feedback = feedback;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public LocalDateTime getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDateTime entryDate) {
        this.entryDate = entryDate;
    }
}
