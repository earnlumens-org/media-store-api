package org.earnlumens.mediastore.application.franchise;

/**
 * Patch payload for a franchise owner editing their own in-app branding.
 * {@code null} means "leave untouched"; an empty string means "clear" (revert
 * to inherited franchisor branding). Owners can never change commission, slug,
 * status, payout wallet or franchisor through this path.
 */
public record FranchiseBrandingUpdate(
    String title,
    String description,
    String logoR2Key,
    String coverR2Key,
    String accentColor
) {}
