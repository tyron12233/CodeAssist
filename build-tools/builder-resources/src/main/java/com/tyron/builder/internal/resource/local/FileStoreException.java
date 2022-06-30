package com.tyron.builder.internal.resource.local;


import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

@Contextual
public class FileStoreException extends BuildException {
    public FileStoreException(String message) {
        super(message);
    }

    public FileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
