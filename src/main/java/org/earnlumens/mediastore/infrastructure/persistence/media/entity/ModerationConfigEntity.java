package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Read-only mapping to the {@code moderationConfigs} collection (managed by admin-api).
 * Used only to fetch the per-tenant business rules prompt at dispatch time.
 */
@Document(collection = "moderationConfigs")
public class ModerationConfigEntity {

    @Id
    private String id;

    private String tenantId;
    private String businessRulesPrompt;
    private String tenantPublishingNotes;

    public String getId() { return id; }

    public String getTenantId() { return tenantId; }

    public String getBusinessRulesPrompt() { return businessRulesPrompt; }

    public String getTenantPublishingNotes() { return tenantPublishingNotes; }
}
