package com.tyron.builder.internal.remote.internal;

import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.remote.internal.MessageIOException;

@Contextual
public class RecoverableMessageIOException extends MessageIOException {
    public RecoverableMessageIOException(String message, Throwable cause) {
        super(message, cause);
    }
}