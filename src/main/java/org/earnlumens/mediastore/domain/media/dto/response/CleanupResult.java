package org.earnlumens.mediastore.domain.media.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record CleanupResult(
        int deletedCount,
        Map<String, Integer> byType,
        LocalDateTime cutoffTime,
        long durationMs
) {}
