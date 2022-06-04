package com.tyron.builder.api.internal.file.archive.compression;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Compresses the input
 */
public interface ArchiveOutputStreamFactory {

    /**
     * Returns the output stream that is able to compress into the destination file
     *
     * @param destination the destination of the archive output stream
     */
    OutputStream createArchiveOutputStream(File destination) throws IOException;
}
