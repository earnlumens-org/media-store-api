package org.earnlumens.mediastore.web.franchise.dto;

import org.earnlumens.mediastore.infrastructure.franchise.write.FranchiseWriteModel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Owner-facing projection of a franchise. Includes the fields the owner manages
 * plus the frozen commission and payout wallet so they can see their own deal —
 * this is only ever returned to the authenticated owner of the franchise.
 */
public record ManagedFranchiseResponse(
    String id,
    String tenantId,
    String slug,
    BigDecimal commissionPercent,
    String payoutWallet,
    String title,
    String description,
    String logoR2Key,
    String coverR2Key,
    String accentColor,
    String status,
    String disabledReason,
    Instant acceptedTermsAt,
    Instant createdAt
) {
    public static ManagedFranchiseResponse of(FranchiseWriteModel f) {
        return new ManagedFranchiseResponse(
            f.getId(),
            f.getTenantId(),
            f.getSlug(),
            f.getCommissionPercent(),
            f.getPayoutWallet(),
            f.getTitle(),
            f.getDescription(),
            f.getLogoR2Key(),
            f.getCoverR2Key(),
            f.getAccentColor(),
            f.getStatus(),
            f.getDisabledReason(),
            f.getAcceptedTermsAt(),
            f.getCreatedAt()
        );
    }
}
