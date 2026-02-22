package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Configuration for the platform payment split.
 * <p>
 * Read from application properties:
 * <pre>
 *   platform.wallet=GXXX...
 *   platform.fee-percent=10.00
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "platform")
public class PlatformConfig {

    /** Stellar public key of the EarnLumens platform wallet */
    private String wallet;

    /** Platform fee percentage (default: 10.00 = 10%) */
    private BigDecimal feePercent = new BigDecimal("10.00");

    public String getWallet() { return wallet; }
    public void setWallet(String wallet) { this.wallet = wallet; }

    public BigDecimal getFeePercent() { return feePercent; }
    public void setFeePercent(BigDecimal feePercent) { this.feePercent = feePercent; }
}
