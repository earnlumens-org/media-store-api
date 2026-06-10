package org.earnlumens.mediastore.infrastructure.r2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;

/**
 * Generates presigned PUT URLs for uploading files directly to Cloudflare R2.
 */
@Service
public class R2PresignedUrlService {

    private static final Logger logger = LoggerFactory.getLogger(R2PresignedUrlService.class);
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(15);

    /** Single-PUT uploads: generous window so slow connections can finish. */
    public static final Duration SINGLE_PUT_DURATION = Duration.ofMinutes(60);

    /** Multipart part URLs: very large files on slow links can take hours. */
    public static final Duration PART_URL_DURATION = Duration.ofHours(24);

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
        return generatePresignedPutUrl(r2Key, contentType, PRESIGN_DURATION);
    }

    /**
     * Generates a presigned PUT URL with a caller-provided validity window.
     */
    public String generatePresignedPutUrl(String r2Key, String contentType, Duration duration) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(r2Key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        String url = presigned.url().toString();

        logger.debug("Generated presigned PUT URL for key={}, bucket={}, validity={}", r2Key, bucket, duration);
        return url;
    }

    /**
     * Generates a presigned URL for uploading one part of a multipart upload.
     */
    public String generatePresignedUploadPartUrl(String r2Key, String s3UploadId, int partNumber) {
        UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                .bucket(bucket)
                .key(r2Key)
                .uploadId(s3UploadId)
                .partNumber(partNumber)
                .build();

        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(PART_URL_DURATION)
                .uploadPartRequest(uploadPartRequest)
                .build();

        PresignedUploadPartRequest presigned = presigner.presignUploadPart(presignRequest);
        return presigned.url().toString();
    }
}
