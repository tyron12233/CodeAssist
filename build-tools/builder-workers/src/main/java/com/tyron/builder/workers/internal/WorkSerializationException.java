package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.exceptions.Contextual;

@Contextual
class WorkSerializationException extends RuntimeException {
    WorkSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
