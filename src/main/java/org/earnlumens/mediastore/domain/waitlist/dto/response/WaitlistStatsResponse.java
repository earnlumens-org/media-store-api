package org.earnlumens.mediastore.domain.waitlist.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class WaitlistStatsResponse {

    private Map<String, Long> stats;

    public WaitlistStatsResponse(Map<String, Long> stats) {
        this.stats = stats;
    }

    @JsonProperty("stats")
    public Map<String, Long> getStats() {
        return stats;
    }

    public void setStats(Map<String, Long> stats) {
        this.stats = stats;
    }
}
