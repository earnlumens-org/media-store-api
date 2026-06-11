package org.earnlumens.mediastore.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
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

    /**
     * Startup sanity check: the platform wallet is a destination of EVERY paid
     * sale, so a missing/malformed value breaks all payments platform-wide.
     * Logged as ERROR (not fail-fast) so dev/test contexts without the env var
     * still boot.
     */
    @PostConstruct
    void validateWallet() {
        if (wallet == null || !wallet.matches("^G[A-Z2-7]{55}$")) {
            LoggerFactory.getLogger(PlatformConfig.class).error(
                    "platform.wallet is missing or malformed ('{}') — ALL paid sales will fail. "
                            + "Set PLATFORM_WALLET to the platform's Stellar public key.", wallet);
        }
    }
}
