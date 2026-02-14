package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "assets")
@CompoundIndex(name = "idx_tenant_entry_kind", def = "{'tenantId': 1, 'entryId': 1, 'kind': 1}")
@CompoundIndex(name = "idx_tenant_r2key", def = "{'tenantId': 1, 'r2Key': 1}", unique = true)
public class AssetEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String entryId;

    @NotBlank
    private String r2Key;

    @NotBlank
    private String contentType;

    @NotBlank
    private String fileName;

    private Long fileSizeBytes;

    @NotBlank
    private String kind;

    @NotBlank
    private String status;

    @CreatedDate
    private LocalDateTime createdAt;

    public AssetEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getR2Key() { return r2Key; }
    public void setR2Key(String r2Key) { this.r2Key = r2Key; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
