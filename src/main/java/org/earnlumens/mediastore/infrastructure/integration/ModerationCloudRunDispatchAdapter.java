package org.earnlumens.mediastore.infrastructure.integration;

import com.google.auth.oauth2.GoogleCredentials;
import org.earnlumens.mediastore.domain.media.model.ModerationJob;
import org.earnlumens.mediastore.domain.media.port.ModerationDispatchPort;
import org.earnlumens.mediastore.infrastructure.config.ModerationConfig;
import org.earnlumens.mediastore.infrastructure.persistence.media.repository.ModerationConfigMongoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cloud Run Jobs v2 adapter for dispatching content moderation jobs.
 *
 * <p>Calls {@code POST /v2/projects/{project}/locations/{region}/jobs/{job}:run}
 * with per-execution container overrides for job-specific environment variables.
 *
 * <p>Injects all required env vars for the moderation-worker pipeline:
 * R2 credentials, ACRCloud, Gemini API key, business rules prompt, and
 * callback URLs.
 */
@Component
public class ModerationCloudRunDispatchAdapter implements ModerationDispatchPort {

    private static final Logger logger = LoggerFactory.getLogger(ModerationCloudRunDispatchAdapter.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final ModerationConfig config;
    private final String moderationSecret;
    private final String r2Endpoint;
    private final String r2AccessKeyId;
    private final String r2SecretAccessKey;
    private final String r2Bucket;
    private final String acrcloudHost;
    private final String acrcloudAccessKey;
    private final String acrcloudAccessSecret;
    private final String geminiApiKey;
    private final ModerationConfigMongoRepository configRepository;
    private final RestClient restClient;
    private GoogleCredentials credentials;

    public ModerationCloudRunDispatchAdapter(
            ModerationConfig config,
            ModerationConfigMongoRepository configRepository,
            @Value("${mediastore.internal.moderationSecret}") String moderationSecret,
            @Value("${mediastore.r2.endpoint:}") String r2Endpoint,
            @Value("${mediastore.r2.accessKeyId:}") String r2AccessKeyId,
            @Value("${mediastore.r2.secretAccessKey:}") String r2SecretAccessKey,
            @Value("${mediastore.r2.bucket:}") String r2Bucket,
            @Value("${mediastore.acrcloud.host:}") String acrcloudHost,
            @Value("${mediastore.acrcloud.accessKey:}") String acrcloudAccessKey,
            @Value("${mediastore.acrcloud.accessSecret:}") String acrcloudAccessSecret,
            @Value("${mediastore.gemini.apiKey:}") String geminiApiKey
    ) {
        this.config = config;
        this.configRepository = configRepository;
        this.moderationSecret = moderationSecret;
        this.r2Endpoint = r2Endpoint;
        this.r2AccessKeyId = r2AccessKeyId;
        this.r2SecretAccessKey = r2SecretAccessKey;
        this.r2Bucket = r2Bucket;
        this.acrcloudHost = acrcloudHost;
        this.acrcloudAccessKey = acrcloudAccessKey;
        this.acrcloudAccessSecret = acrcloudAccessSecret;
        this.geminiApiKey = geminiApiKey;
        this.restClient = RestClient.create();
        initCredentials();
    }

    private void initCredentials() {
        try {
            this.credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (IOException e) {
            logger.warn("Google Application Default Credentials not available — "
                    + "Cloud Run moderation dispatch will be disabled. Error: {}",
                    e.getMessage());
            this.credentials = null;
        }
    }

    @Override
    public void dispatch(ModerationJob job) {
        if (credentials == null) {
            logger.info("Google credentials not available — retrying ADC init for moderation dispatch");
            initCredentials();
        }
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Cloud Run moderation dispatch not configured — cannot dispatch job " + job.getId()
                    + ". Set mediastore.moderation.cloud-run-* properties and ensure Google ADC is available.");
        }

        String url = String.format(
                "https://run.googleapis.com/v2/projects/%s/locations/%s/jobs/%s:run",
                config.getCloudRunProjectId(),
                config.getCloudRunRegion(),
                config.getCloudRunJobName()
        );

        String callbackUrl = config.getCallbackBaseUrl() + "/api/internal/moderation/complete";
        String heartbeatUrl = config.getCallbackBaseUrl() + "/api/internal/moderation/heartbeat";

        List<Map<String, String>> envVars = new ArrayList<>();
        envVars.add(env("JOB_ID", job.getId()));
        envVars.add(env("ENTRY_ID", job.getEntryId()));
        envVars.add(env("TENANT_ID", job.getTenantId()));
        envVars.add(env("ENTRY_TYPE", job.getEntryType().name()));
        envVars.add(env("SOURCE_R2_KEY", job.getSourceR2Key()));
        envVars.add(env("CALLBACK_URL", callbackUrl));
        envVars.add(env("HEARTBEAT_URL", heartbeatUrl));
        envVars.add(env("MODERATION_SECRET", moderationSecret));

        // R2 credentials
        envVars.add(env("R2_ENDPOINT", r2Endpoint));
        envVars.add(env("R2_ACCESS_KEY_ID", r2AccessKeyId));
        envVars.add(env("R2_SECRET_ACCESS_KEY", r2SecretAccessKey));
        envVars.add(env("R2_BUCKET", r2Bucket));

        // Entry metadata for Gemini text analysis
        if (job.getEntryTitle() != null) envVars.add(env("ENTRY_TITLE", job.getEntryTitle()));
        if (job.getEntryDescription() != null) envVars.add(env("ENTRY_DESCRIPTION", job.getEntryDescription()));
        if (job.getEntryTags() != null) envVars.add(env("ENTRY_TAGS", job.getEntryTags()));
        if (job.getThumbnailR2Key() != null) envVars.add(env("THUMBNAIL_R2_KEY", job.getThumbnailR2Key()));

        // ACRCloud credentials
        if (isNotBlank(acrcloudHost)) envVars.add(env("ACRCLOUD_HOST", acrcloudHost));
        if (isNotBlank(acrcloudAccessKey)) envVars.add(env("ACRCLOUD_ACCESS_KEY", acrcloudAccessKey));
        if (isNotBlank(acrcloudAccessSecret)) envVars.add(env("ACRCLOUD_ACCESS_SECRET", acrcloudAccessSecret));

        // Gemini API key
        envVars.add(env("GEMINI_API_KEY", geminiApiKey));

        // Business rules prompt from admin config (shared MongoDB)
        configRepository.findByTenantId(job.getTenantId()).ifPresent(cfg -> {
            if (isNotBlank(cfg.getBusinessRulesPrompt())) {
                envVars.add(env("BUSINESS_RULES_PROMPT", cfg.getBusinessRulesPrompt()));
            }
        });

        Map<String, Object> payload = Map.of(
                "overrides", Map.of(
                        "containerOverrides", List.of(Map.of(
                                "env", envVars
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

            logger.info("Dispatched moderation job {} to Cloud Run: project={}, region={}, cloudJob={}",
                    job.getId(), config.getCloudRunProjectId(),
                    config.getCloudRunRegion(), config.getCloudRunJobName());
        } catch (IOException e) {
            throw new RuntimeException("Failed to refresh Google credentials for moderation dispatch", e);
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
