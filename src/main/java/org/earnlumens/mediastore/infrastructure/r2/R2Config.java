package org.earnlumens.mediastore.infrastructure.r2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Configures an {@link S3Presigner} bean pointing at the Cloudflare R2 endpoint.
 * R2 is S3-compatible, so we reuse the AWS SDK with a custom endpoint.
 */
@Configuration
public class R2Config {

    @Value("${mediastore.r2.endpoint}")
    private String endpoint;

    @Value("${mediastore.r2.accessKeyId}")
    private String accessKeyId;

    @Value("${mediastore.r2.secretAccessKey}")
    private String secretAccessKey;

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
