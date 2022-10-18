package org.gradle.internal.remote.internal;

public class MessageIOException extends RuntimeException {
    public MessageIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
