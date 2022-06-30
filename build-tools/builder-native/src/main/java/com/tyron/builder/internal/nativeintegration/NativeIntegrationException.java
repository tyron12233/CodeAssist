package com.tyron.builder.internal.nativeintegration;

public class NativeIntegrationException extends RuntimeException {
    public NativeIntegrationException(String message) {
        super(message);
    }

    public NativeIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
