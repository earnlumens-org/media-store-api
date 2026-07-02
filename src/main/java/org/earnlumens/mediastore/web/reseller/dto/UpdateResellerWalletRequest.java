package org.earnlumens.mediastore.web.reseller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request to change the payout wallet of an owned reseller link. */
public record UpdateResellerWalletRequest(
        @NotBlank @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String wallet
) {}
