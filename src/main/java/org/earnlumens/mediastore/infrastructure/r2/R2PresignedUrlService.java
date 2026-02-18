package org.earnlumens.mediastore.infrastructure.r2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

/**
 * Generates presigned PUT URLs for uploading files directly to Cloudflare R2.
 */
@Service
public class R2PresignedUrlService {

    private static final Logger logger = LoggerFactory.getLogger(R2PresignedUrlService.class);
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(15);

    private final S3Presigner presigner;
    private final String bucket;

    public R2PresignedUrlService(
            S3Presigner presigner,
            @Value("${mediastore.r2.bucket}") String bucket
    ) {
        this.presigner = presigner;
        this.bucket = bucket;
    }

    /**
     * Generates a presigned PUT URL for a given R2 key and content type.
     *
     * @param r2Key       the object key in the bucket
     * @param contentType the MIME type of the file
     * @return the presigned PUT URL (valid for 15 minutes)
     */
    public String generatePresignedPutUrl(String r2Key, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(r2Key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_DURATION)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        String url = presigned.url().toString();

        logger.debug("Generated presigned PUT URL for key={}, bucket={}", r2Key, bucket);
        return url;
    }
}
