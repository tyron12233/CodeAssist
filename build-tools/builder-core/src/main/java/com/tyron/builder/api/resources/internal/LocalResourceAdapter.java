package com.tyron.builder.api.resources.internal;

import com.tyron.builder.internal.resource.LocalBinaryResource;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;

/**
 * Adapts a {@link LocalBinaryResource} to a {@link ReadableResourceInternal}.
 */
public class LocalResourceAdapter implements ReadableResourceInternal {
    private final LocalBinaryResource resource;

    public LocalResourceAdapter(LocalBinaryResource resource) {
        this.resource = resource;
    }

    @Override
    public File getBackingFile() {
        return resource.getContainingFile();
    }

    @Override
    public String toString() {
        return resource.getDisplayName();
    }

    @Override
    public String getDisplayName() {
        return resource.getDisplayName();
    }

    @Override
    public InputStream read() {
        return new BufferedInputStream(resource.open());
    }

    @Override
    public URI getURI() {
        return resource.getURI();
    }

    @Override
    public String getBaseName() {
        return resource.getBaseName();
    }
}
