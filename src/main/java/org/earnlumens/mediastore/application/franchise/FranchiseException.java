package org.earnlumens.mediastore.application.franchise;

/**
 * Service-layer exception mapped by the franchise controller to an HTTP status
 * + stable {@link FranchiseErrorCode}.
 */
public class FranchiseException extends RuntimeException {

    private final FranchiseErrorCode errorCode;
    private final int httpStatus;

    public FranchiseException(FranchiseErrorCode errorCode, int httpStatus) {
        super(errorCode.code());
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public FranchiseErrorCode getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
