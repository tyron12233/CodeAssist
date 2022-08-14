package org.gradle.internal.nativeintegration;

/**
 * Thrown when the native integration for the current platform is not available for some reason (eg unsupported operating system, cannot load native library, etc).
 */
public class NativeIntegrationUnavailableException extends NativeIntegrationException {
    public NativeIntegrationUnavailableException(String message) {
        super(message);
    }
}
