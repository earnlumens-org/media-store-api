package org.earnlumens.mediastore.web.franchise.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request to mint a presigned PUT URL for a franchise branding image. The
 * franchise is identified by the path; the body only declares which slot is
 * being replaced and the file's content-type and size so the server can lock
 * the presign and reject oversized or non-image uploads up front.
 */
public class FranchiseImageUploadRequest {

    /** "logo" or "cover". */
    @NotBlank
    private String slot;

    /** MIME type, e.g. "image/png". */
    @NotBlank
    private String contentType;

    @Positive
    private long fileSizeBytes;

    public String getSlot() { return slot; }
    public void setSlot(String slot) { this.slot = slot; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
}
