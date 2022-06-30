package com.tyron.builder.internal.resource;

import com.tyron.builder.api.resources.ResourceException;

import java.net.URI;

/**
 * Exception thrown when one attempts to read a folder
 */
public class ResourceIsAFolderException extends ResourceException {
    public ResourceIsAFolderException(URI location, String message) {
        super(location, message);
    }
}
