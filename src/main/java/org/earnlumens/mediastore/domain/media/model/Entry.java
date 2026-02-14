package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

public class Entry {

    private String id;
    private String tenantId;
    private String userId;
    private String r2Key;
    private String contentType;
    private String fileName;
    private MediaKind kind;
    private MediaVisibility visibility;
    private Long fileSizeBytes;
    private LocalDateTime createdAt;

    public Entry() {}

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

    public MediaKind getKind() {
        return kind;
    }

    public void setKind(MediaKind kind) {
        this.kind = kind;
    }

    public MediaVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(MediaVisibility visibility) {
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
