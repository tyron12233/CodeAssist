package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface StreamHasher {
    /**
     * Returns the hash of the given input stream. The stream will not be closed by the method.
     */
    HashCode hash(InputStream inputStream);

    /**
     * Returns the hash of the given input stream while copying the data to the output stream.
     * The method will not close either stream.
     */
    HashCode hashCopy(InputStream inputStream, OutputStream outputStream) throws IOException;
}