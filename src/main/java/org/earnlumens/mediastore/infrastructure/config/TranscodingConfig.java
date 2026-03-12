package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the HLS transcoding pipeline.
 * <p>
 * Read from application properties:
 * <pre>
 *   mediastore.transcoding.max-retries=3
 *   mediastore.transcoding.heartbeat-timeout-seconds=120
 *   mediastore.transcoding.watchdog-interval-ms=30000
 *   mediastore.transcoding.dispatch-batch-size=10
 *   mediastore.transcoding.stale-batch-size=50
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "mediastore.transcoding")
public class TranscodingConfig {

    /** Maximum retry attempts before a job is marked DEAD. */
    private int maxRetries = 3;

    /** Seconds without heartbeat before a job is considered stale. */
    private int heartbeatTimeoutSeconds = 120;

    /** Watchdog polling interval in milliseconds. */
    private long watchdogIntervalMs = 30_000;

    /** Maximum number of PENDING jobs to dispatch per cycle. */
    private int dispatchBatchSize = 10;

    /** Maximum number of stale jobs to recover per watchdog cycle. */
    private int staleBatchSize = 50;

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }

    public long getWatchdogIntervalMs() { return watchdogIntervalMs; }
    public void setWatchdogIntervalMs(long watchdogIntervalMs) { this.watchdogIntervalMs = watchdogIntervalMs; }

    public int getDispatchBatchSize() { return dispatchBatchSize; }
    public void setDispatchBatchSize(int dispatchBatchSize) { this.dispatchBatchSize = dispatchBatchSize; }

    public int getStaleBatchSize() { return staleBatchSize; }
    public void setStaleBatchSize(int staleBatchSize) { this.staleBatchSize = staleBatchSize; }
}
