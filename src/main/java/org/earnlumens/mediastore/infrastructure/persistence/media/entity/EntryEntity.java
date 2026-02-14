package org.earnlumens.mediastore.infrastructure.persistence.media.entity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "entries")
@CompoundIndex(name = "idx_tenant_id", def = "{'tenantId': 1, '_id': 1}", unique = true)
@CompoundIndex(name = "idx_tenant_user", def = "{'tenantId': 1, 'userId': 1}")
public class EntryEntity {

    @Id
    private String id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String userId;

    @NotBlank
    private String r2Key;

    private String contentType;

    private String fileName;

    @NotBlank
    private String kind;

    @NotBlank
    private String visibility;

    private Long fileSizeBytes;

    @CreatedDate
    private LocalDateTime createdAt;

    public EntryEntity() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getR2Key() {
        return r2Key;
    }

    public void setR2Key(String r2Key) {
        this.r2Key = r2Key;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
