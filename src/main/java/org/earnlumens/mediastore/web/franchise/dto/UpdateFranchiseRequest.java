package org.earnlumens.mediastore.web.franchise.dto;

import jakarta.validation.constraints.Size;

/**
 * Franchise owner edits their own in-app branding. Patch semantics: {@code null}
 * leaves the value untouched, empty string clears it (reverting to inherited
 * franchisor branding). The owner can NEVER change commission, slug, payout
 * wallet, status or franchisor through this path.
 */
public class UpdateFranchiseRequest {

    @Size(max = 80)
    private String title;

    @Size(max = 280)
    private String description;

    @Size(max = 256)
    private String logoR2Key;

    @Size(max = 256)
    private String coverR2Key;

    @Size(max = 9)
    private String accentColor;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLogoR2Key() { return logoR2Key; }
    public void setLogoR2Key(String logoR2Key) { this.logoR2Key = logoR2Key; }

    public String getCoverR2Key() { return coverR2Key; }
    public void setCoverR2Key(String coverR2Key) { this.coverR2Key = coverR2Key; }

    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }
}
