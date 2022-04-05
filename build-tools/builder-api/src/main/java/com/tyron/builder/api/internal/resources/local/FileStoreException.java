package com.tyron.builder.api.internal.resources.local;


import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.exceptions.Contextual;

@Contextual
public class FileStoreException extends BuildException {
    public FileStoreException(String message) {
        super(message);
    }

    public FileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
