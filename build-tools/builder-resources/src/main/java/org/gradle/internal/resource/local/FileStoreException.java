package org.gradle.internal.resource.local;


import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class FileStoreException extends GradleException {
    public FileStoreException(String message) {
        super(message);
    }

    public FileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
