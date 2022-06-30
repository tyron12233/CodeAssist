package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.internal.exceptions.Contextual;

/**
 * Signals that HTTP response has been received successfully but an error code is encountered (neither 2xx/3xx nor 404).
 */
@Contextual
public class HttpErrorStatusCodeException extends RuntimeException {

    private final int statusCode;

    public HttpErrorStatusCodeException(String method, String source, int statusCode, String reason) {
        super(String.format("Could not %s '%s'. Received status code %s from server: %s",
            method, source, statusCode, reason));
        this.statusCode = statusCode;
    }

    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
