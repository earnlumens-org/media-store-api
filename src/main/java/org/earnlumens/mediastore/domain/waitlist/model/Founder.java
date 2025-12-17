package org.earnlumens.mediastore.domain.waitlist.model;

import java.time.LocalDateTime;

public class Founder {

    private String id;
    private LocalDateTime entryDate;
    private String email;

    public Founder() {
    }

    public Founder(String email) {
        this.email = email;
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
}
