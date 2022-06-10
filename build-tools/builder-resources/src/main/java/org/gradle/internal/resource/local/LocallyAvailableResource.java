package org.gradle.internal.resource.local;

import com.google.common.hash.HashCode;
import org.gradle.internal.resource.Resource;

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
