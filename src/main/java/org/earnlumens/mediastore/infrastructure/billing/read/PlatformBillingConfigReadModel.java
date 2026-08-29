package org.earnlumens.mediastore.infrastructure.billing.read;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

/**
 * Read-only mirror of admin-api's {@code PlatformBillingConfig} singleton
 * (collection {@code platform_billing_config}). media-store-api only reads
 * the global Pro plan prices to build plan orders; admin-api is the writer.
 * Defaults match the closed pricing decision (US$4.99 / US$49.00) so plan
 * purchases work even before the SUPERADMIN ever saves the document.
 */
@Document(collection = "platform_billing_config")
public class PlatformBillingConfigReadModel {

    public static final String SINGLETON_ID = "billing";

    public static final BigDecimal DEFAULT_MONTHLY_USD = new BigDecimal("4.99");
    public static final BigDecimal DEFAULT_YEARLY_USD = new BigDecimal("49.00");

    @Id
    private String id;

    private BigDecimal planPriceMonthlyUsd;
    private BigDecimal planPriceYearlyUsd;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public BigDecimal getPlanPriceMonthlyUsd() {
        return planPriceMonthlyUsd == null ? DEFAULT_MONTHLY_USD : planPriceMonthlyUsd;
    }
    public void setPlanPriceMonthlyUsd(BigDecimal planPriceMonthlyUsd) {
        this.planPriceMonthlyUsd = planPriceMonthlyUsd;
    }

    public BigDecimal getPlanPriceYearlyUsd() {
        return planPriceYearlyUsd == null ? DEFAULT_YEARLY_USD : planPriceYearlyUsd;
    }
    public void setPlanPriceYearlyUsd(BigDecimal planPriceYearlyUsd) {
        this.planPriceYearlyUsd = planPriceYearlyUsd;
    }
}
