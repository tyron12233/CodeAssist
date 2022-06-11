package org.gradle.api.services.internal;

import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

import javax.annotation.Nullable;

@Contextual
class ServiceLifecycleException extends GradleException {
    public ServiceLifecycleException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
