package org.earnlumens.mediastore.infrastructure.external.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class R2StorageConfig {

    @Value("${r2.access-key}")
    private String accessKey;

    @Value("${r2.secret-key}")
    private String secretKey;

    @Value("${r2.endpoint}")
    private String endpoint;

    @Value("${r2.bucket}")
    private String bucket;

    @Value("${r2.public-url:}")
    private String publicUrl;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(true)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    @Bean
    public R2Properties r2Properties() {
        return new R2Properties(bucket, publicUrl, endpoint);
    }

    public record R2Properties(String bucket, String publicUrl, String endpoint) {}
}
