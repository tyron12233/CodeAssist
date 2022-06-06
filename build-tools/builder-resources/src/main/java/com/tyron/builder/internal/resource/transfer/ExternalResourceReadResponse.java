package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * A single use read of some resource. Don't use this class directly - use the {@link com.tyron.builder.internal.resource.ExternalResource} wrapper instead.
 */
public interface ExternalResourceReadResponse extends Closeable {
    InputStream openStream() throws IOException;

    ExternalResourceMetaData getMetaData();
}
