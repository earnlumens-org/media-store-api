package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to prepare a PUBLISH_FAST_PASS payment.
 * <p>
 * A FastPass adds one extra slot to the NEXT open publishing block of the
 * space and enqueues the entity into it on payment confirmation. Available
 * only while the block's base slots are sold out; guarantees inclusion, never
 * position. The price is the space's configured USD amount (default $2)
 * converted to XLM server-side. Irreversible; grants no entitlement.
 */
public record PrepareFastPassRequest(
        @NotBlank
        String spaceId,
        /** Publishing entity type name (e.g. "ENTRY"). */
        @NotBlank
        String entityType,
        @NotBlank
        String entityId,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String buyerWallet
) {}
