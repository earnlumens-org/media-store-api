package org.earnlumens.mediastore.web.franchise.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A logged-in user creates a franchise ("beta") under the franchisor tenant
 * whose storefront they are on. The tenant is taken from the request host, NOT
 * from the client, so it is intentionally absent here. Commission is frozen
 * from the franchisor default server-side and is never client-supplied.
 */
public class CreateFranchiseRequest {

    /** URL slug under {@code /f/<slug>}. Lowercase, RFC-1123-safe. */
    @NotBlank
    @Size(min = 3, max = 30)
    private String slug;

    /** Stellar public key (G…) for commission payouts. */
    @NotBlank
    private String payoutWallet;

    /** Commercial display title (optional; inherits franchisor title when blank). */
    @Size(max = 80)
    private String title;

    @Size(max = 280)
    private String description;

    /** Accent colour hex (e.g. {@code #1E88E5}). Optional. */
    @Size(max = 9)
    private String accentColor;

    /** Explicit acceptance of the (frozen) franchise terms. */
    private boolean acceptTerms;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getPayoutWallet() { return payoutWallet; }
    public void setPayoutWallet(String payoutWallet) { this.payoutWallet = payoutWallet; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }

    public boolean isAcceptTerms() { return acceptTerms; }
    public void setAcceptTerms(boolean acceptTerms) { this.acceptTerms = acceptTerms; }
}
