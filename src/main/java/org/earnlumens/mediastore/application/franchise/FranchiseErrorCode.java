package org.earnlumens.mediastore.application.franchise;

/**
 * Strongly-typed error codes returned by the franchisee self-service flow.
 * Mapped to a JSON response of shape {@code {"error":"<code>"}} by the
 * controller. Codes are kept identical to admin-api's
 * {@code org.earnlumens.admin.franchise.FranchiseErrorCode} so a single shared
 * frontend error-localisation map works against either service.
 */
public enum FranchiseErrorCode {

    FRANCHISES_NOT_ENABLED,     // The franchisor tenant has not opted into franchises.
    FRANCHISES_PAUSED,          // New franchise creation is currently paused.
    USER_BANNED,                // Caller is banned from franchises under this tenant.
    SLUG_REQUIRED,
    SLUG_FORMAT,
    SLUG_TAKEN,
    WALLET_FORMAT,
    WALLET_NOT_ACTIVATED,       // Payout wallet does not exist (is unfunded) on the Stellar network.
    ACCENT_COLOR_FORMAT,
    IMAGE_SLOT,                 // Branding image slot must be "logo" or "cover".
    IMAGE_TYPE,                 // Unsupported image content-type.
    IMAGE_SIZE,                 // Image exceeds the per-slot byte limit.
    IMAGE_KEY,                  // r2Key does not live under this franchise's namespace.
    NOT_FOUND,
    FORBIDDEN,
    TENANT_BLOCKED;

    public String code() { return name().toLowerCase(); }
}
