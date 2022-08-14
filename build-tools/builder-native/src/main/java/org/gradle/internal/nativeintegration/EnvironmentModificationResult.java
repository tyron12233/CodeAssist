package org.gradle.internal.nativeintegration;

/**
 * Encapsulates what happened when we tried to modify the environment.
 */
public enum EnvironmentModificationResult {
    SUCCESS(null),
    UNSUPPORTED_ENVIRONMENT("There is no native integration with this operating environment.");

    private final String reason;

    EnvironmentModificationResult(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return reason;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
