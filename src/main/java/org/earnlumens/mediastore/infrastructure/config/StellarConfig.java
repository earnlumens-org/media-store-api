package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Stellar network configuration.
 * Reads from application.properties (prefix: stellar).
 *
 * Environment variables in Cloud Run:
 *   STELLAR_HORIZON_URL, STELLAR_NETWORK_PASSPHRASE
 */
@Configuration
@ConfigurationProperties(prefix = "stellar")
public class StellarConfig {

    /** Horizon API base URL */
    private String horizonUrl = "https://horizon-testnet.stellar.org";

    /** Network passphrase (testnet or public) */
    private String networkPassphrase = "Test SDF Network ; September 2015";

    /** Transaction time-bounds window in seconds (default 5 minutes) */
    private int txTimeoutSeconds = 300;

    public String getHorizonUrl() { return horizonUrl; }
    public void setHorizonUrl(String horizonUrl) { this.horizonUrl = horizonUrl; }

    public String getNetworkPassphrase() { return networkPassphrase; }
    public void setNetworkPassphrase(String networkPassphrase) { this.networkPassphrase = networkPassphrase; }

    public int getTxTimeoutSeconds() { return txTimeoutSeconds; }
    public void setTxTimeoutSeconds(int txTimeoutSeconds) { this.txTimeoutSeconds = txTimeoutSeconds; }
}
