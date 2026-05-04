package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the thumbnail processing pipeline.
 *
 * <p>Read from application properties:
 * <pre>
 *   mediastore.thumbnail.max-retries=2
 *   mediastore.thumbnail.heartbeat-timeout-seconds=120
 *   mediastore.thumbnail.watchdog-interval-ms=30000
 *   mediastore.thumbnail.dispatch-batch-size=10
 *   mediastore.thumbnail.dispatch-interval-ms=10000
 *   mediastore.thumbnail.stale-batch-size=50
 *   mediastore.thumbnail.cloud-run-project-id=...
 *   mediastore.thumbnail.cloud-run-region=europe-west1
 *   mediastore.thumbnail.cloud-run-job-name=thumbnail-process
 *   mediastore.thumbnail.callback-base-url=https://api.earnlumens.org
 *   # Variant widths (longest side, in pixels) emitted by the worker.
 *   mediastore.thumbnail.variant-widths=320,640,1280
 *   # Minimum allowed input dimension (shortest side). Below this the worker
 *   # SKIPs processing and the original is served as-is.
 *   mediastore.thumbnail.min-shortest-side-px=480
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "mediastore.thumbnail")
public class ThumbnailConfig {

    /** Maximum retry attempts before a job is marked DEAD. Kept low — original is the fallback. */
    private int maxRetries = 2;

    /** Seconds without heartbeat before a job is considered stale. */
    private int heartbeatTimeoutSeconds = 120;

    /** Watchdog polling interval in milliseconds. */
    private long watchdogIntervalMs = 30_000;

    /** Maximum number of PENDING jobs to dispatch per cycle. */
    private int dispatchBatchSize = 10;

    /** Dispatcher polling interval in milliseconds. */
    private long dispatchIntervalMs = 10_000;

    /** Maximum number of stale jobs to recover per watchdog cycle. */
    private int staleBatchSize = 50;

    private String cloudRunProjectId = "";
    private String cloudRunRegion = "europe-west1";
    private String cloudRunJobName = "";
    private String callbackBaseUrl = "";

    /** Comma-separated variant widths emitted by the worker (longest side, pixels). */
    private String variantWidths = "320,640,1280";

    /** Minimum shortest-side in pixels. Below this, worker skips and original is used. */
    private int minShortestSidePx = 480;

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }

    public long getWatchdogIntervalMs() { return watchdogIntervalMs; }
    public void setWatchdogIntervalMs(long watchdogIntervalMs) { this.watchdogIntervalMs = watchdogIntervalMs; }

    public int getDispatchBatchSize() { return dispatchBatchSize; }
    public void setDispatchBatchSize(int dispatchBatchSize) { this.dispatchBatchSize = dispatchBatchSize; }

    public long getDispatchIntervalMs() { return dispatchIntervalMs; }
    public void setDispatchIntervalMs(long dispatchIntervalMs) { this.dispatchIntervalMs = dispatchIntervalMs; }

    public int getStaleBatchSize() { return staleBatchSize; }
    public void setStaleBatchSize(int staleBatchSize) { this.staleBatchSize = staleBatchSize; }

    public String getCloudRunProjectId() { return cloudRunProjectId; }
    public void setCloudRunProjectId(String cloudRunProjectId) { this.cloudRunProjectId = cloudRunProjectId; }

    public String getCloudRunRegion() { return cloudRunRegion; }
    public void setCloudRunRegion(String cloudRunRegion) { this.cloudRunRegion = cloudRunRegion; }

    public String getCloudRunJobName() { return cloudRunJobName; }
    public void setCloudRunJobName(String cloudRunJobName) { this.cloudRunJobName = cloudRunJobName; }

    public String getCallbackBaseUrl() { return callbackBaseUrl; }
    public void setCallbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; }

    public String getVariantWidths() { return variantWidths; }
    public void setVariantWidths(String variantWidths) { this.variantWidths = variantWidths; }

    public int getMinShortestSidePx() { return minShortestSidePx; }
    public void setMinShortestSidePx(int minShortestSidePx) { this.minShortestSidePx = minShortestSidePx; }
}
