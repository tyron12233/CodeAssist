package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public class DefaultFileHasher implements FileHasher {
    private final StreamHasher streamHasher;

    public DefaultFileHasher(StreamHasher streamHasher) {
        this.streamHasher = streamHasher;
    }

    @Override
    public HashCode hash(File file) {
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(String.format("Failed to create MD5 hash for file '%s' as it does not exist.", file), e);
        }
        try {
            return streamHasher.hash(inputStream);
        } finally {
            try {
                inputStream.close();
            } catch (IOException ignored) {
                // Ignored
            }
        }
    }

    @Override
    public HashCode hash(File file, long length, long lastModified) {
        return hash(file);
    }
}