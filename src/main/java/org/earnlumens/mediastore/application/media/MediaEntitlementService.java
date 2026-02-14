package org.earnlumens.mediastore.application.media;

import org.earnlumens.mediastore.domain.media.dto.response.MediaEntitlementResponse;
import org.earnlumens.mediastore.domain.media.model.Entry;
import org.earnlumens.mediastore.domain.media.model.EntitlementStatus;
import org.earnlumens.mediastore.domain.media.repository.EntitlementRepository;
import org.earnlumens.mediastore.domain.media.repository.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MediaEntitlementService {

    private static final Logger logger = LoggerFactory.getLogger(MediaEntitlementService.class);

    private final EntryRepository entryRepository;
    private final EntitlementRepository entitlementRepository;

    public MediaEntitlementService(EntryRepository entryRepository, EntitlementRepository entitlementRepository) {
        this.entryRepository = entryRepository;
        this.entitlementRepository = entitlementRepository;
    }

    /**
     * Checks whether a user is entitled to access a private media entry.
     * Access is granted if the user is the content owner OR has an ACTIVE entitlement.
     *
     * @param tenantId the tenant identifier
     * @param userId   the requesting user's OAuth user ID (JWT subject)
     * @param entryId  the entry identifier
     * @return the entitlement response if allowed, empty otherwise
     */
    public Optional<MediaEntitlementResponse> checkEntitlement(String tenantId, String userId, String entryId) {
        Optional<Entry> optEntry = entryRepository.findByTenantIdAndId(tenantId, entryId);
        if (optEntry.isEmpty()) {
            logger.debug("Entry not found: tenantId={}, entryId={}", tenantId, entryId);
            return Optional.empty();
        }

        Entry entry = optEntry.get();

        // Owner always has access
        if (userId.equals(entry.getUserId())) {
            logger.debug("Access granted (owner): userId={}, entryId={}", userId, entryId);
            return Optional.of(buildResponse(entry));
        }

        // Check active entitlement
        boolean entitled = entitlementRepository
                .existsByTenantIdAndUserIdAndEntryIdAndStatus(tenantId, userId, entryId, EntitlementStatus.ACTIVE);

        if (!entitled) {
            logger.debug("Access denied: tenantId={}, userId={}, entryId={}", tenantId, userId, entryId);
            return Optional.empty();
        }

        logger.debug("Access granted (entitlement): userId={}, entryId={}", userId, entryId);
        return Optional.of(buildResponse(entry));
    }

    private MediaEntitlementResponse buildResponse(Entry entry) {
        return new MediaEntitlementResponse(
                true,
                entry.getR2Key(),
                entry.getContentType(),
                computeContentDisposition(entry),
                entry.getFileName()
        );
    }

    private String computeContentDisposition(Entry entry) {
        String ct = entry.getContentType();
        String fn = entry.getFileName();

        boolean inline = ct != null && (
                ct.startsWith("image/") ||
                ct.startsWith("video/") ||
                ct.startsWith("audio/") ||
                "application/pdf".equals(ct)
        );

        String disposition = inline ? "inline" : "attachment";
        if (fn != null && !fn.isBlank()) {
            // Escape quotes in filename
            String safeName = fn.replace("\"", "\\\"");
            disposition += "; filename=\"" + safeName + "\"";
        }
        return disposition;
    }
}
