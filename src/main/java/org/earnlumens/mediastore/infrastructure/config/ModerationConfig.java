package org.earnlumens.mediastore.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the content moderation pipeline.
 * <p>
 * Read from application properties:
 * <pre>
 *   mediastore.moderation.max-retries=3
 *   mediastore.moderation.heartbeat-timeout-seconds=90
 *   mediastore.moderation.watchdog-interval-ms=15000
 *   mediastore.moderation.dispatch-batch-size=20
 *   mediastore.moderation.dispatch-interval-ms=5000
 *   mediastore.moderation.dispatch-threads=4
 *   mediastore.moderation.stale-batch-size=20
 *   mediastore.moderation.cloud-run-project-id=...
 *   mediastore.moderation.cloud-run-region=europe-west1
 *   mediastore.moderation.cloud-run-job-name=moderate-content
 *   mediastore.moderation.callback-base-url=https://api.earnlumens.org
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "mediastore.moderation")
public class ModerationConfig {

    private int maxRetries = 3;
    private int heartbeatTimeoutSeconds = 90;
    private long watchdogIntervalMs = 15_000;
    private int dispatchBatchSize = 20;
    private long dispatchIntervalMs = 5_000;
    private int dispatchThreads = 4;
    private int staleBatchSize = 20;
    private String cloudRunProjectId = "";
    private String cloudRunRegion = "europe-west1";
    private String cloudRunJobName = "";
    private String callbackBaseUrl = "";

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

    public int getDispatchThreads() { return dispatchThreads; }
    public void setDispatchThreads(int dispatchThreads) { this.dispatchThreads = dispatchThreads; }

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
}
