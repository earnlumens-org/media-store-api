package org.earnlumens.mediastore.domain.media.model;

import java.time.LocalDateTime;

public class Asset {

    private String id;
    private String tenantId;
    private String entryId;
    private String r2Key;
    private String contentType;
    private String fileName;
    private Long fileSizeBytes;
    private MediaKind kind;
    private AssetStatus status;
    private Integer widthPx;
    private Integer heightPx;
    private Integer durationSec;
    private String codecVideo;
    private String codecAudio;
    private Long bitrateBps;
    private LocalDateTime createdAt;

    public Asset() {}

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
    public MediaKind getKind() { return kind; }
    public void setKind(MediaKind kind) { this.kind = kind; }
    public AssetStatus getStatus() { return status; }
    public void setStatus(AssetStatus status) { this.status = status; }
    public Integer getWidthPx() { return widthPx; }
    public void setWidthPx(Integer widthPx) { this.widthPx = widthPx; }
    public Integer getHeightPx() { return heightPx; }
    public void setHeightPx(Integer heightPx) { this.heightPx = heightPx; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public String getCodecVideo() { return codecVideo; }
    public void setCodecVideo(String codecVideo) { this.codecVideo = codecVideo; }
    public String getCodecAudio() { return codecAudio; }
    public void setCodecAudio(String codecAudio) { this.codecAudio = codecAudio; }
    public Long getBitrateBps() { return bitrateBps; }
    public void setBitrateBps(Long bitrateBps) { this.bitrateBps = bitrateBps; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
