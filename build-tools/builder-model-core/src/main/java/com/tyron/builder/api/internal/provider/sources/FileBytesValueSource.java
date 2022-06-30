package com.tyron.builder.api.internal.provider.sources;

import com.tyron.builder.api.UncheckedIOException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public abstract class FileBytesValueSource extends FileContentValueSource<byte[]> {

    @Override
    protected byte[] obtainFrom(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
