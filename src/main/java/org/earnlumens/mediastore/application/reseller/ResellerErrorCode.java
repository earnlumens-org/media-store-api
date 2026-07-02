package org.earnlumens.mediastore.application.reseller;

/**
 * Strongly-typed error codes returned by the reseller self-service flow.
 * Mapped to a JSON response of shape {@code {"error":"<code>"}} by the
 * controller and localised on the frontend.
 */
public enum ResellerErrorCode {

    ENTRY_NOT_FOUND,            // Entry does not exist under this tenant.
    ENTRY_NOT_RESELLABLE,       // Entry is free, or resells are disabled by the creator.
    OWN_CONTENT,                // Caller is the content creator — cannot resell own content.
    WALLET_FORMAT,             // Reseller wallet is not a valid Stellar public key.
    WALLET_NOT_ACTIVATED,       // Reseller wallet does not exist (is unfunded) on the Stellar network.
    NOT_FOUND,                  // Link not found / not owned by caller.
    FORBIDDEN;

    public String code() { return name().toLowerCase(); }
}
