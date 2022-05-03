package com.tyron.builder.internal.resource;

import java.io.File;

public abstract class AbstractExternalResource implements ExternalResource {
    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public ExternalResourceReadResult<Void> writeTo(File destination) {
        ExternalResourceReadResult<Void> result = writeToIfPresent(destination);
        if (result == null) {
            throw ResourceExceptions.getMissing(getURI());
        }
        return result;
    }

    @Override
    public <T> ExternalResourceReadResult<T> withContent(ContentAction<? extends T> readAction) {
        ExternalResourceReadResult<T> result = withContentIfPresent(readAction);
        if (result == null) {
            throw ResourceExceptions.getMissing(getURI());
        }
        return result;
    }

    @Override
    public <T> ExternalResourceReadResult<T> withContent(ContentAndMetadataAction<? extends T> readAction) {
        ExternalResourceReadResult<T> result = withContentIfPresent(readAction);
        if (result == null) {
            throw ResourceExceptions.getMissing(getURI());
        }
        return result;
    }
}
