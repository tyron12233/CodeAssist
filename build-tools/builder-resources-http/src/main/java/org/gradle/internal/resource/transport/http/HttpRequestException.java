package org.gradle.internal.resource.transport.http;

import org.gradle.api.UncheckedIOException;
import org.gradle.internal.exceptions.Contextual;

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
