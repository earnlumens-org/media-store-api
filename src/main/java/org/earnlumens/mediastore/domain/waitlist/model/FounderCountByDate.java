package org.earnlumens.mediastore.domain.waitlist.model;

import java.time.LocalDateTime;

public class FounderCountByDate {

    private LocalDateTime entryDate;
    private int totalFounders;

    public LocalDateTime getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDateTime entryDate) {
        this.entryDate = entryDate;
    }

    public int getTotalFounders() {
        return totalFounders;
    }

    public void setTotalFounders(int totalFounders) {
        this.totalFounders = totalFounders;
    }
}
