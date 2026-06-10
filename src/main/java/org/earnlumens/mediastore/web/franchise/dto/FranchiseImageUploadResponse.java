package org.earnlumens.mediastore.web.franchise.dto;

import org.earnlumens.mediastore.application.franchise.FranchiseImagePresign;

/**
 * Presigned upload target for a franchise branding image. The client PUTs the
 * file to {@code presignedUrl}, then persists {@code r2Key} via the franchise
 * PATCH endpoint.
 */
public record FranchiseImageUploadResponse(String presignedUrl, String r2Key) {

    public static FranchiseImageUploadResponse of(FranchiseImagePresign p) {
        return new FranchiseImageUploadResponse(p.presignedUrl(), p.r2Key());
    }
}
