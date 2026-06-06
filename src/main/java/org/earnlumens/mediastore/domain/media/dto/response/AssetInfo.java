package org.earnlumens.mediastore.domain.media.dto.response;

/**
 * Lightweight asset metadata returned in public entry responses.
 * Does not contain download URLs — content delivery is handled
 * by the CDN Worker via /media/{entryId} with entitlement check.
 */
public record AssetInfo(
        String fileName,
        Long fileSizeBytes,
        String contentType,
        /** ISO-8601 timestamp of the malware scan (ClamAV) that cleared this file; null if not recorded. */
        java.time.LocalDateTime scannedAt
) {}
