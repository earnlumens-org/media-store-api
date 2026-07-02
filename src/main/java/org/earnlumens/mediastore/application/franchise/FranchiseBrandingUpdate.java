package org.earnlumens.mediastore.application.franchise;

/**
 * Patch payload for a franchise owner editing their own in-app branding and
 * payout wallet. {@code null} means "leave untouched"; an empty string means
 * "clear" (revert to inherited franchisor branding). The payout wallet may be
 * replaced (never cleared) and is fully re-validated by the service. Owners
 * can never change commission, slug, status or franchisor through this path.
 */
public record FranchiseBrandingUpdate(
    String title,
    String description,
    String logoR2Key,
    String coverR2Key,
    String accentColor,
    String payoutWallet
) {}
