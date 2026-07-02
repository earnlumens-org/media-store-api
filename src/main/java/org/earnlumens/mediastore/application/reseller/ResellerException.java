package org.earnlumens.mediastore.application.reseller;

/**
 * Service-layer exception mapped by the reseller controller to an HTTP status
 * + stable {@link ResellerErrorCode}.
 */
public class ResellerException extends RuntimeException {

    private final ResellerErrorCode errorCode;
    private final int httpStatus;

    public ResellerException(ResellerErrorCode errorCode, int httpStatus) {
        super(errorCode.code());
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ResellerErrorCode getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
