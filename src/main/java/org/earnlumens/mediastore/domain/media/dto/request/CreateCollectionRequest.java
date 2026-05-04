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

        /**
         * Content language. Either an ISO 639-1 code ("es", "en", "zh-cn", ...)
         * or the special value "multi" for collections that mix languages or
         * have no dominant language.
         * <p>
         * This is the user-declared default. The moderation pipeline is the
         * source of truth and may overwrite this value.
         */
        @Pattern(regexp = "^[a-z]{2}(-[a-z]{2})?$",
                message = "contentLanguage must be a lowercase ISO 639-1 code (e.g. 'en', 'es', 'zh-cn'); the value 'multi' is reserved for the moderation pipeline and cannot be set by users")
        String contentLanguage
) {}
