package org.earnlumens.mediastore.web.franchise;

import org.earnlumens.mediastore.infrastructure.franchise.read.FranchiseReadModel;

/**
 * Public storefront projection of a franchise ("beta"). Only the fields needed
 * to render the franchise storefront and its branding override are exposed —
 * never the payout wallet, commission or owner identity beyond a display name.
 */
public record PublicFranchiseResponse(
    String slug,
    String tenantId,
    String title,
    String description,
    String logoR2Key,
    String coverR2Key,
    String accentColor,
    String ownerDisplayName
) {
    public static PublicFranchiseResponse of(FranchiseReadModel f) {
        return new PublicFranchiseResponse(
            f.getSlug(),
            f.getTenantId(),
            f.getTitle(),
            f.getDescription(),
            f.getLogoR2Key(),
            f.getCoverR2Key(),
            f.getAccentColor(),
            f.getOwnerDisplayName()
        );
    }
}
