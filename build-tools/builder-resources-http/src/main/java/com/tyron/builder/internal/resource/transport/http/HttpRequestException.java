package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.exceptions.Contextual;

/**
 * Signals that some error occurred when making an HTTP request.
 * This is different from a HTTP request returning an HTTP error code.
 */
@Contextual
public class HttpRequestException extends UncheckedIOException {
    public HttpRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
