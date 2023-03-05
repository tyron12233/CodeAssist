package org.gradle.internal.remote.internal;

public class ConnectException extends RuntimeException {
    public ConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}