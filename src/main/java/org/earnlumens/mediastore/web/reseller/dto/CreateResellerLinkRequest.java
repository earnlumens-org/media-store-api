package org.earnlumens.mediastore.web.reseller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request to generate (or fetch the existing) reseller link for an entry. */
public record CreateResellerLinkRequest(
        @NotBlank
        String entryId,
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String wallet
) {}
