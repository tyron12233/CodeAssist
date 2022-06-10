package org.gradle.internal.resource.local;


import org.gradle.api.BuildException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class FileStoreException extends BuildException {
    public FileStoreException(String message) {
        super(message);
    }

    public FileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
