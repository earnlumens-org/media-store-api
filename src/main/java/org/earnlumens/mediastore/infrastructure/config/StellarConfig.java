package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Stellar network configuration.
 * Reads from application.properties (prefix: stellar).
 *
 * Environment variables in Cloud Run:
 *   STELLAR_HORIZON_URL, STELLAR_HORIZON_URLS, STELLAR_NETWORK_PASSPHRASE
 */
@Configuration
@ConfigurationProperties(prefix = "stellar")
public class StellarConfig {

    /** Horizon API base URL (single-node fallback when horizonUrls is empty) */
    private String horizonUrl = "https://horizon-testnet.stellar.org";

    /**
     * Comma-separated list of Horizon base URLs, in priority order, used by
     * {@code HorizonServerPool} for read rotation and submit failover.
     * First entry receives transaction submissions; reads round-robin across
     * all of them. Future self-hosted Horizon nodes go first in this list.
     * When empty, the pool degrades to the single {@link #horizonUrl}.
     */
    private String horizonUrls = "";

    /** Network passphrase (testnet or public) */
    private String networkPassphrase = "Test SDF Network ; September 2015";

    /** Transaction time-bounds window in seconds (default 5 minutes) */
    private int txTimeoutSeconds = 300;

    public String getHorizonUrl() { return horizonUrl; }
    public void setHorizonUrl(String horizonUrl) { this.horizonUrl = horizonUrl; }

    public String getHorizonUrls() { return horizonUrls; }
    public void setHorizonUrls(String horizonUrls) { this.horizonUrls = horizonUrls; }

    /**
     * Parsed, trimmed, deduplicated Horizon URL list (priority order).
     * Falls back to {@link #horizonUrl} when the list property is unset.
     */
    public List<String> getHorizonUrlList() {
        if (horizonUrls != null && !horizonUrls.isBlank()) {
            List<String> parsed = Arrays.stream(horizonUrls.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                    .distinct()
                    .toList();
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return List.of(horizonUrl);
    }

    public String getNetworkPassphrase() { return networkPassphrase; }
    public void setNetworkPassphrase(String networkPassphrase) { this.networkPassphrase = networkPassphrase; }

    public int getTxTimeoutSeconds() { return txTimeoutSeconds; }
    public void setTxTimeoutSeconds(int txTimeoutSeconds) { this.txTimeoutSeconds = txTimeoutSeconds; }
}
