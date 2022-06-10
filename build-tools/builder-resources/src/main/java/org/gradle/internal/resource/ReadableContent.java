package org.gradle.internal.resource;

import org.gradle.api.resources.ResourceException;

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
