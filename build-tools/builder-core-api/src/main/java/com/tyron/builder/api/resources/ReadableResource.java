package com.tyron.builder.api.resources;

import java.io.InputStream;
import java.util.MissingResourceException;

/**
 * A resource that can be read. The simplest example is a file.
 */
//@HasInternalProtocol
public interface ReadableResource extends Resource {
    /**
     * Returns an unbuffered {@link InputStream} that provides means to read the resource. It is the caller's responsibility to close this stream.
     *
     * @return An input stream.
     */
    InputStream read() throws MissingResourceException, ResourceException;
}