package org.gradle.workers.internal;

import org.gradle.internal.exceptions.Contextual;

@Contextual
class WorkSerializationException extends RuntimeException {
    WorkSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
