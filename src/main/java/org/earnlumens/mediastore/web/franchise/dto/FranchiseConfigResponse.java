package org.earnlumens.mediastore.web.franchise.dto;

import org.earnlumens.mediastore.application.franchise.FranchiseConfigView;

import java.math.BigDecimal;

/**
 * Tells the "create my franchise" surface whether sign-ups are open on the
 * current storefront and on what commission, so the UI can show the deal up
 * front and gate the form.
 */
public record FranchiseConfigResponse(
    boolean franchisesEnabled,
    boolean franchisesPaused,
    boolean banned,
    BigDecimal defaultCommissionPercent,
    boolean available
) {
    public static FranchiseConfigResponse of(FranchiseConfigView v) {
        return new FranchiseConfigResponse(
            v.franchisesEnabled(),
            v.franchisesPaused(),
            v.banned(),
            v.defaultCommissionPercent(),
            v.available()
        );
    }
}
