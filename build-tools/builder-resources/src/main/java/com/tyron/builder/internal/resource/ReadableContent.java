package com.tyron.builder.internal.resource;

import com.tyron.builder.api.resources.ResourceException;

import java.io.InputStream;

/**
 * Some resource content with a known length.
 */
public interface ReadableContent {
    /**
     * Unbuffered input stream to read contents of resource.
     */
    InputStream open() throws ResourceException;

    long getContentLength();
}
