package org.earnlumens.mediastore.domain.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateCollectionRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 2000)
        String description,

        @NotNull
        String collectionType,

        /** PUBLIC or PRIVATE. Defaults to PUBLIC. */
        String visibility,

        @NotNull
        Boolean isPaid,

        BigDecimal priceXlm,

        BigDecimal priceUsd,

        String priceCurrency,

        @Size(min = 56, max = 56)
        @Pattern(regexp = "^G[A-Z2-7]{55}$", message = "Invalid Stellar public key")
        String sellerWallet,

        /** ISO 639-1 language code of the content (e.g. "es", "en"). Optional. */
        @Size(min = 2, max = 5)
        String contentLanguage
) {}
