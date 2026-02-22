package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import java.math.BigDecimal;

/**
 * Embedded sub-document representing a single payment split recipient.
 * Stored inside {@link EntryEntity#paymentSplits} as a list in MongoDB.
 */
public class PaymentSplitEntity {

    /** Stellar public key (G...) */
    private String wallet;

    /** PLATFORM, SELLER, or COLLABORATOR */
    private String role;

    /** Percentage of total price (e.g. "10.00" = 10%) */
    private BigDecimal percent;

    public PaymentSplitEntity() {}

    public String getWallet() { return wallet; }
    public void setWallet(String wallet) { this.wallet = wallet; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public BigDecimal getPercent() { return percent; }
    public void setPercent(BigDecimal percent) { this.percent = percent; }
}
