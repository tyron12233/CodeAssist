package org.gradle.api.services.internal;

import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

import javax.annotation.Nullable;

@Contextual
class ServiceLifecycleException extends BuildException {
    public ServiceLifecycleException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
