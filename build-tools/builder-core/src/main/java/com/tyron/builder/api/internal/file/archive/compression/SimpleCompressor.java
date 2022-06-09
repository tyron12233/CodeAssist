package com.tyron.builder.api.internal.file.archive.compression;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class SimpleCompressor implements ArchiveOutputStreamFactory {

    @Override
    public OutputStream createArchiveOutputStream(File destination) {
        try {
            return new FileOutputStream(destination);
        } catch (Exception e) {
            String message = String.format("Unable to create output stream for file %s.", destination);
            throw new RuntimeException(message, e);
        }
    }
}
