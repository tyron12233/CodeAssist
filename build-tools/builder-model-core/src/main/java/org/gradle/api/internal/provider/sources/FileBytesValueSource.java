package org.gradle.api.internal.provider.sources;

import org.gradle.api.UncheckedIOException;

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
