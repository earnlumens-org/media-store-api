package org.earnlumens.mediastore.domain.waitlist.model;

import java.time.LocalDateTime;

public class Founder {

    private String id;
    private LocalDateTime entryDate;
    private String email;
    private String userId;

    public Founder() {
    }

    public Founder(String email) {
        this.email = email;
    }

    public Founder(String email, String userId) {
        this.email = email;
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDateTime entryDate) {
        this.entryDate = entryDate;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
