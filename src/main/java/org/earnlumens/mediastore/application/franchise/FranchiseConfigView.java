package org.earnlumens.mediastore.application.franchise;

import java.math.BigDecimal;

/**
 * Whether the current tenant accepts franchise sign-ups, for the franchisee
 * "create my franchise" surface. {@code available} folds together every gate
 * (enabled, not paused, tenant active, caller not banned) so the UI can decide
 * with a single flag, while the individual fields let it explain <i>why</i>.
 */
public record FranchiseConfigView(
    boolean franchisesEnabled,
    boolean franchisesPaused,
    boolean banned,
    BigDecimal defaultCommissionPercent,
    boolean available
) {}
