package org.gradle.internal.remote.internal;

import org.gradle.internal.exceptions.Contextual;
import org.gradle.internal.remote.internal.MessageIOException;

@Contextual
public class RecoverableMessageIOException extends MessageIOException {
    public RecoverableMessageIOException(String message, Throwable cause) {
        super(message, cause);
    }
}