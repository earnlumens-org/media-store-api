package org.earnlumens.mediastore.application.franchise;

/**
 * Result of minting a presigned PUT URL for a franchise branding image
 * (logo or cover). The caller uploads the file directly to R2 with
 * {@code presignedUrl}, then persists {@code r2Key} via the franchise PATCH
 * so a stalled upload never leaves the franchise pointing at a missing object.
 */
public record FranchiseImagePresign(String presignedUrl, String r2Key) {
}
