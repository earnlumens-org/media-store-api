package org.earnlumens.mediastore.infrastructure.integration;

import com.google.auth.oauth2.GoogleCredentials;
import org.earnlumens.mediastore.domain.media.model.TranscodingJob;
import org.earnlumens.mediastore.domain.media.port.TranscodingDispatchPort;
import org.earnlumens.mediastore.infrastructure.config.TranscodingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Cloud Run Jobs v2 adapter for dispatching transcoding jobs.
 *
 * <p>Calls {@code POST /v2/projects/{project}/locations/{region}/jobs/{job}:run}
 * with per-execution container overrides for job-specific environment variables.
 *
 * <p>If Google Application Default Credentials are not available (e.g., local dev
 * without {@code gcloud auth application-default login}), dispatch is silently
 * skipped with a warning log. Similarly, if Cloud Run properties are not configured,
 * dispatch is a no-op.
 */
@Component
public class CloudRunDispatchAdapter implements TranscodingDispatchPort {

    private static final Logger logger = LoggerFactory.getLogger(CloudRunDispatchAdapter.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final TranscodingConfig config;
    private final String transcodingSecret;
    private final RestClient restClient;
    private GoogleCredentials credentials;

    public CloudRunDispatchAdapter(
            TranscodingConfig config,
            @Value("${mediastore.internal.transcodingSecret}") String transcodingSecret
    ) {
        this.config = config;
        this.transcodingSecret = transcodingSecret;
        this.restClient = RestClient.create();
        initCredentials();
    }

    private void initCredentials() {
        try {
            this.credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (IOException e) {
            logger.warn("Google Application Default Credentials not available — "
                    + "Cloud Run dispatch will be disabled. "
                    + "Run 'gcloud auth application-default login' for local dev. Error: {}",
                    e.getMessage());
            this.credentials = null;
        }
    }

    @Override
    public void dispatch(TranscodingJob job) {
        if (!isConfigured()) {
            logger.warn("Cloud Run dispatch not configured — skipping job {}. "
                    + "Set mediastore.transcoding.cloud-run-* properties and ensure "
                    + "Google ADC is available.", job.getId());
            return;
        }

        String url = String.format(
                "https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run",
                config.getCloudRunProjectId(),
                config.getCloudRunRegion(),
                config.getCloudRunJobName()
        );

        String callbackUrl = config.getCallbackBaseUrl() + "/api/internal/transcoding/complete";
        String heartbeatUrl = config.getCallbackBaseUrl() + "/api/internal/transcoding/heartbeat";

        Map<String, Object> payload = Map.of(
                "overrides", Map.of(
                        "containerOverrides", List.of(Map.of(
                                "env", List.of(
                                        env("JOB_ID", job.getId()),
                                        env("SOURCE_R2_KEY", job.getSourceR2Key()),
                                        env("ENTRY_ID", job.getEntryId()),
                                        env("TENANT_ID", job.getTenantId()),
                                        env("ASSET_ID", job.getAssetId()),
                                        env("CALLBACK_URL", callbackUrl),
                                        env("HEARTBEAT_URL", heartbeatUrl),
                                        env("TRANSCODING_SECRET", transcodingSecret)
                                )
                        ))
                )
        );

        try {
            credentials.refreshIfExpired();
            String token = credentials.getAccessToken().getTokenValue();

            restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            logger.info("Dispatched transcoding job {} to Cloud Run: project={}, region={}, cloudJob={}",
                    job.getId(), config.getCloudRunProjectId(),
                    config.getCloudRunRegion(), config.getCloudRunJobName());
        } catch (IOException e) {
            throw new RuntimeException("Failed to refresh Google credentials for Cloud Run dispatch", e);
        }
    }

    private boolean isConfigured() {
        return credentials != null
                && isNotBlank(config.getCloudRunProjectId())
                && isNotBlank(config.getCloudRunJobName())
                && isNotBlank(config.getCallbackBaseUrl());
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Map<String, String> env(String name, String value) {
        return Map.of("name", name, "value", value);
    }
}
