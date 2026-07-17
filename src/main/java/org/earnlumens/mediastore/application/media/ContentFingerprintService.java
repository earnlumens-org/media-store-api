package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.infrastructure.r2.R2StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Computes the Original First content fingerprint of an uploaded FULL asset.
 *
 * <p>The fingerprint is a SHA-256 digest over:
 * <ol>
 *   <li>the exact file size (8-byte big-endian), and</li>
 *   <li>up to three 64 KiB samples read server-side from R2:
 *       head, middle and tail of the object (the whole object when it is
 *       small enough that the samples would overlap).</li>
 * </ol>
 *
 * <p>Because the bytes are sampled from R2 <em>after</em> the client upload
 * completes, the fingerprint is authoritative — a client cannot spoof it.
 * Sampling keeps finalize latency flat regardless of file size (three ranged
 * GETs of 64 KiB each) while the size prefix plus three spread samples make
 * accidental collisions between different files practically impossible.
 *
 * <p>Known limitation (documented in ORIGINAL-FIRST.md): a re-encoded or
 * trimmed copy produces different bytes and therefore a different
 * fingerprint. Those cases are handled by the "Claim as Original" flow.
 */
@Service
public class ContentFingerprintService {

    private static final Logger logger = LoggerFactory.getLogger(ContentFingerprintService.class);

    /** Sample window size: 64 KiB. */
    static final int SAMPLE_SIZE = 64 * 1024;
    /** Files up to 3 samples long are hashed whole (no point sampling). */
    static final long SMALL_FILE_THRESHOLD = 3L * SAMPLE_SIZE;

    private final R2StorageService r2StorageService;

    public ContentFingerprintService(R2StorageService r2StorageService) {
        this.r2StorageService = r2StorageService;
    }

    /**
     * Computes the fingerprint for the object at {@code r2Key}.
     *
     * @param r2Key     R2 key of the finalized FULL asset
     * @param sizeBytes authoritative object size (from HeadObject)
     * @return lowercase hex SHA-256, or empty if the object could not be read
     *         (fingerprinting is best-effort and must never fail an upload)
     */
    public Optional<String> compute(String r2Key, long sizeBytes) {
        if (sizeBytes <= 0) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(sizeBytes).array());

            if (sizeBytes <= SMALL_FILE_THRESHOLD) {
                Optional<byte[]> all = r2StorageService.getObjectRange(r2Key, 0, sizeBytes - 1);
                if (all.isEmpty()) return Optional.empty();
                digest.update(all.get());
            } else {
                long midStart = (sizeBytes / 2) - (SAMPLE_SIZE / 2);
                long tailStart = sizeBytes - SAMPLE_SIZE;
                for (long start : new long[]{0, midStart, tailStart}) {
                    Optional<byte[]> chunk = r2StorageService.getObjectRange(
                            r2Key, start, start + SAMPLE_SIZE - 1);
                    if (chunk.isEmpty()) return Optional.empty();
                    digest.update(chunk.get());
                }
            }
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (RuntimeException e) {
            logger.warn("contentFingerprint: failed for key={} size={}: {}",
                    r2Key, sizeBytes, e.getMessage());
            return Optional.empty();
        }
    }
}
