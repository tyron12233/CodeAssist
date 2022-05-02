package com.tyron.builder.internal.resource;

import java.io.File;
import java.net.URI;

/**
 * Some binary resource available somewhere on the local file system.
 */
public interface LocalBinaryResource extends Resource, ReadableContent {
    URI getURI();

    String getBaseName();

    /**
     * Returns the file containing this resource. Note that the content of this resource may not be the same as the file (for example, the file may be compressed, or this resource may represent an entry in an archive file, or both)
     */
    File getContainingFile();
}
