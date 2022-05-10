package com.tyron.builder.internal.resource.local;

import com.tyron.builder.internal.resource.ExternalResource;

import java.io.File;

/**
 * Represents an external resource whose meta-data and content is available locally. The content and meta-data may be a copy of some original resource and the original may or may not be a local resource.
 */
public interface LocallyAvailableExternalResource extends ExternalResource {
    /**
     * Returns a local file containing the content of this resource. This may nor may not be the original resource.
     */
    File getFile();
}
