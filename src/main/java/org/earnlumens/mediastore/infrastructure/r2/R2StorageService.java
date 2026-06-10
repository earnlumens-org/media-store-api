package org.earnlumens.mediastore.infrastructure.r2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-side R2 object operations (HEAD, multipart lifecycle, prefix delete).
 * Complements {@link R2PresignedUrlService}, which only signs client URLs.
 */
@Service
public class R2StorageService {

    private static final Logger logger = LoggerFactory.getLogger(R2StorageService.class);

    private final S3Client s3Client;
    private final String bucket;

    public R2StorageService(
            S3Client s3Client,
            @Value("${mediastore.r2.bucket}") String bucket
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /**
     * Returns object metadata when the key exists, or empty when it does not.
     * Used by /finalize to verify the client actually uploaded the bytes.
     */
    public Optional<HeadObjectResponse> headObject(String r2Key) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(r2Key)
                    .build());
            return Optional.of(head);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Starts a multipart upload and returns the S3 upload id. */
    public String createMultipartUpload(String r2Key, String contentType) {
        var response = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(r2Key)
                .contentType(contentType)
                .build());
        logger.info("R2 multipart created: key={}, s3UploadId={}", r2Key, response.uploadId());
        return response.uploadId();
    }

    /**
     * Completes a multipart upload using the parts R2 itself reports via
     * ListParts. This deliberately ignores client-supplied ETags so the
     * browser never needs CORS access to the ETag response header.
     *
     * @return the number of parts assembled
     */
    public int completeMultipartUpload(String r2Key, String s3UploadId) {
        List<CompletedPart> parts = new ArrayList<>();
        String marker = null;
        do {
            ListPartsResponse listed = s3Client.listParts(ListPartsRequest.builder()
                    .bucket(bucket)
                    .key(r2Key)
                    .uploadId(s3UploadId)
                    .partNumberMarker(marker == null ? null : Integer.valueOf(marker))
                    .build());
            listed.parts().forEach(p -> parts.add(CompletedPart.builder()
                    .partNumber(p.partNumber())
                    .eTag(p.eTag())
                    .build()));
            marker = Boolean.TRUE.equals(listed.isTruncated())
                    ? String.valueOf(listed.nextPartNumberMarker())
                    : null;
        } while (marker != null);

        if (parts.isEmpty()) {
            throw new IllegalArgumentException("UPLOAD_NOT_FOUND");
        }

        parts.sort(java.util.Comparator.comparingInt(CompletedPart::partNumber));

        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(r2Key)
                .uploadId(s3UploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                .build());

        logger.info("R2 multipart completed: key={}, parts={}", r2Key, parts.size());
        return parts.size();
    }

    /** Aborts a multipart upload (best-effort — already-aborted is fine). */
    public void abortMultipartUpload(String r2Key, String s3UploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(r2Key)
                    .uploadId(s3UploadId)
                    .build());
            logger.info("R2 multipart aborted: key={}", r2Key);
        } catch (S3Exception e) {
            logger.warn("R2 multipart abort failed (ignored): key={}, status={}", r2Key, e.statusCode());
        }
    }

    /** Deletes a single object (best-effort). */
    public void deleteObject(String r2Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(r2Key)
                    .build());
        } catch (S3Exception e) {
            logger.warn("R2 delete failed (ignored): key={}, status={}", r2Key, e.statusCode());
        }
    }

    /**
     * Deletes every object under the given prefix. Used to reclaim storage
     * when an entry (and its uploads) are removed.
     *
     * @return number of objects deleted
     */
    public int deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !prefix.contains("/")) {
            // Refuse degenerate prefixes that could wipe the bucket.
            logger.error("deleteByPrefix: refusing suspicious prefix '{}'", prefix);
            return 0;
        }

        int deleted = 0;
        String continuationToken = null;
        do {
            ListObjectsV2Response listed = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());

            List<ObjectIdentifier> keys = listed.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .toList();

            if (!keys.isEmpty()) {
                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(keys).build())
                        .build());
                deleted += keys.size();
            }

            continuationToken = Boolean.TRUE.equals(listed.isTruncated())
                    ? listed.nextContinuationToken()
                    : null;
        } while (continuationToken != null);

        if (deleted > 0) {
            logger.info("R2 deleteByPrefix: removed {} object(s) under '{}'", deleted, prefix);
        }
        return deleted;
    }
}
