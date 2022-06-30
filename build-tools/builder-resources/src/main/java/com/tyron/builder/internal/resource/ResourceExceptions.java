package com.tyron.builder.internal.resource;

import com.tyron.builder.api.resources.MissingResourceException;
import com.tyron.builder.api.resources.ResourceException;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;

public class ResourceExceptions {
    public static ResourceIsAFolderException readFolder(File location) {
        return new ResourceIsAFolderException(location.toURI(), String.format("Cannot read '%s' because it is a folder.", location));
    }

    public static ResourceException readFailed(File location, Throwable failure) {
        return failure(location.toURI(), String.format("Could not read '%s'.", location), failure);
    }

    public static ResourceException readFailed(String displayName, Throwable failure) {
        return new ResourceException(String.format("Could not read %s.", displayName), failure);
    }

    public static MissingResourceException readMissing(File location, Throwable failure) {
        return new MissingResourceException(location.toURI(),
                String.format("Could not read '%s' as it does not exist.", location),
                failure instanceof FileNotFoundException ? null : failure);
    }

    public static MissingResourceException getMissing(URI location, Throwable failure) {
        return new MissingResourceException(location,
                String.format("Could not read '%s' as it does not exist.", location),
                failure instanceof FileNotFoundException ? null : failure);
    }

    public static MissingResourceException getMissing(URI location) {
        return new MissingResourceException(location,
                String.format("Could not read '%s' as it does not exist.", location));
    }

    public static ResourceException getFailed(URI location, Throwable failure) {
        return failure(location, String.format("Could not get resource '%s'.", location), failure);
    }

    public static ResourceException putFailed(URI location, Throwable failure) {
        return failure(location, String.format("Could not write to resource '%s'.", location), failure);
    }

    /**
     * Wraps the given failure, unless it is a ResourceException with the specified location.
     */
    public static ResourceException failure(URI location, String message, Throwable failure) {
        if (failure instanceof ResourceException) {
            ResourceException resourceException = (ResourceException) failure;
            if (location.equals(resourceException.getLocation())) {
                return resourceException;
            }
        }
        return new ResourceException(location, message, failure);
    }
}
