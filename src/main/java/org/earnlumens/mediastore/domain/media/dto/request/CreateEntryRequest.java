package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateEntryRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 2000)
        String description,

        @Size(max = 50000)
        String resourceContent,

        @NotNull
        String type,

        @NotNull
        Boolean isPaid,

        BigDecimal priceXlm,

        BigDecimal priceUsd,

        /** "XLM" or "USD". Defaults to XLM if null/absent (backward compatible). */
        String priceCurrency,

        /**
         * Stellar public key (G...) of the seller's connected wallet.
         * Required when isPaid = true. Must be a valid Stellar public key (56 chars starting with G).
         */
        @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String sellerWallet,

        /** ISO 639-1 language code of the content (e.g. "es", "en"). Optional. */
        @Size(min = 2, max = 5)
        String contentLanguage
) {}
