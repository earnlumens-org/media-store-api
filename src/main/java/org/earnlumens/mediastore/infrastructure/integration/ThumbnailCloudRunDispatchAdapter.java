package org.earnlumens.mediastore.infrastructure.integration;

import com.google.auth.oauth2.GoogleCredentials;
import org.earnlumens.mediastore.domain.media.model.ThumbnailJob;
import org.earnlumens.mediastore.domain.media.port.ThumbnailDispatchPort;
import org.earnlumens.mediastore.infrastructure.config.ThumbnailConfig;
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
 * Cloud Run Jobs v2 adapter for dispatching thumbnail-processing jobs.
 *
 * <p>Mirrors {@code CloudRunDispatchAdapter} for transcoding — same auth,
 * same {@code containerOverrides.env} pattern, distinct Cloud Run Job and secret.
 */
@Component
public class ThumbnailCloudRunDispatchAdapter implements ThumbnailDispatchPort {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailCloudRunDispatchAdapter.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final ThumbnailConfig config;
    private final String thumbnailSecret;
    private final RestClient restClient;
    private GoogleCredentials credentials;

    public ThumbnailCloudRunDispatchAdapter(
            ThumbnailConfig config,
            @Value("${mediastore.internal.thumbnailSecret}") String thumbnailSecret
    ) {
        this.config = config;
        this.thumbnailSecret = thumbnailSecret;
        this.restClient = RestClient.create();
        initCredentials();
    }

    private void initCredentials() {
        try {
            this.credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (IOException e) {
            logger.warn("Google Application Default Credentials not available — "
                    + "Cloud Run thumbnail dispatch will be disabled. "
                    + "Run 'gcloud auth application-default login' for local dev. Error: {}",
                    e.getMessage());
            this.credentials = null;
        }
    }

    @Override
    public void dispatch(ThumbnailJob job) {
        if (credentials == null) {
            logger.info("Google credentials not available — retrying ADC init for thumbnail dispatch");
            initCredentials();
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Cloud Run thumbnail dispatch not configured — cannot dispatch job " + job.getId()
                    + ". Set mediastore.thumbnail.cloud-run-* properties and ensure Google ADC is available.");
        }

        String url = String.format(
                "https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run",
                config.getCloudRunProjectId(),
                config.getCloudRunRegion(),
                config.getCloudRunJobName()
        );

        String callbackUrl = config.getCallbackBaseUrl() + "/api/internal/thumbnail/complete";
        String heartbeatUrl = config.getCallbackBaseUrl() + "/api/internal/thumbnail/heartbeat";

        Map<String, Object> payload = Map.of(
                "overrides", Map.of(
                        "containerOverrides", List.of(Map.of(
                                "env", List.of(
                                        env("JOB_ID", job.getId()),
                                        env("TENANT_ID", job.getTenantId()),
                                        env("OWNER_ID", job.getOwnerId()),
                                        env("KIND", job.getKind().name()),
                                        env("SOURCE_R2_KEY", job.getSourceR2Key()),
                                        env("OUTPUT_R2_PREFIX", job.getOutputR2Prefix()),
                                        env("VARIANT_WIDTHS", config.getVariantWidths()),
                                        env("MIN_SHORTEST_SIDE_PX",
                                                Integer.toString(config.getMinShortestSidePx())),
                                        env("CALLBACK_URL", callbackUrl),
                                        env("HEARTBEAT_URL", heartbeatUrl),
                                        env("THUMBNAIL_SECRET", thumbnailSecret)
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

            logger.info("Dispatched thumbnail job {} to Cloud Run: project={}, region={}, cloudJob={}, kind={}",
                    job.getId(), config.getCloudRunProjectId(),
                    config.getCloudRunRegion(), config.getCloudRunJobName(), job.getKind());
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
