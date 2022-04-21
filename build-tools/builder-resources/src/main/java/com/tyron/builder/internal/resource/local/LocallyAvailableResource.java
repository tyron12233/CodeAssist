package com.tyron.builder.internal.resource.local;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.resource.Resource;

import java.io.File;

/**
 * Represents a file backed local resource.
 */
public interface LocallyAvailableResource extends Resource {

    File getFile();

    HashCode getSha1();

    long getLastModified();

    long getContentLength();
}
