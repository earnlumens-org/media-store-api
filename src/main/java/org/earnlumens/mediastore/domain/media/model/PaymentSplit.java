package org.earnlumens.mediastore.domain.media.model;

import java.math.BigDecimal;

/**
 * A single payment split recipient within an Entry.
 * <p>
 * Embedded inside Entry.paymentSplits as a list of sub-documents in MongoDB.
 * Each split defines a wallet, a role, and a percentage of the total price.
 * <p>
 * The sum of all split percentages for a given Entry must equal exactly 100.00.
 * BigDecimal is used for all monetary/percentage calculations to avoid
 * floating-point precision issues.
 */
public class PaymentSplit {

    /** Stellar public key (G...) of the recipient */
    private String wallet;

    /** Role of this recipient in the payment distribution */
    private SplitRole role;

    /** Percentage of the total price allocated to this recipient (e.g. 10.00 = 10%) */
    private BigDecimal percent;

    public PaymentSplit() {}

    public PaymentSplit(String wallet, SplitRole role, BigDecimal percent) {
        this.wallet = wallet;
        this.role = role;
        this.percent = percent;
    }

    public String getWallet() { return wallet; }
    public void setWallet(String wallet) { this.wallet = wallet; }

    public SplitRole getRole() { return role; }
    public void setRole(SplitRole role) { this.role = role; }

    public BigDecimal getPercent() { return percent; }
    public void setPercent(BigDecimal percent) { this.percent = percent; }
}
