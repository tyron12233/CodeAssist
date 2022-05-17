package com.tyron.builder.language.base.internal.resolve;

import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;

@Contextual
public class LibraryResolveException extends DefaultMultiCauseException {

    public LibraryResolveException(String message) {
        super(message);
    }

    public LibraryResolveException(String message, Iterable<? extends Throwable> causes) {
        super(message, causes);
    }
}
