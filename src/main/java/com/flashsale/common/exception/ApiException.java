package com.flashsale.common.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException conflict(String errorCode, String message) {
        return new ApiException(HttpStatus.CONFLICT, errorCode, message);
    }

    public static ApiException notFound(String errorCode, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, errorCode, message);
    }

    public HttpStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
