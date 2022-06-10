package org.gradle.internal.resource.transfer;

import org.gradle.internal.resource.metadata.ExternalResourceMetaData;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * A single use read of some resource. Don't use this class directly - use the {@link org.gradle.internal.resource.ExternalResource} wrapper instead.
 */
public interface ExternalResourceReadResponse extends Closeable {
    InputStream openStream() throws IOException;

    ExternalResourceMetaData getMetaData();
}
