package com.tyron.builder.api.internal.file.temp;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.util.internal.CollectionUtils;
import com.tyron.builder.util.internal.GFileUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

public class DefaultTemporaryFileProvider implements TemporaryFileProvider, Serializable {
    private final Factory<File> baseDirFactory;

    public DefaultTemporaryFileProvider(final Factory<File> fileFactory) {
        this.baseDirFactory = fileFactory;
    }

    @Override
    public File newTemporaryFile(String... path) {
        return GFileUtils.canonicalize(new File(baseDirFactory.create(), CollectionUtils.join("/", path)));
    }

    @Override
    public File createTemporaryFile(String prefix, @Nullable String suffix, String... path) {
        File dir = newTemporaryFile(path);
        GFileUtils.mkdirs(dir);
        try {
            return TempFiles.createTempFile(prefix, suffix, dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public File createTemporaryDirectory(String prefix, @Nullable String suffix, String... path) {
        File dir = newTemporaryFile(path);
        GFileUtils.mkdirs(dir);
        try {
            // TODO: This is not a great paradigm for creating a temporary directory.
            // See http://guava-libraries.googlecode.com/svn/tags/release08/javadoc/com/google/common/io/Files.html#createTempDir%28%29 for an alternative.
            File tmpDir = TempFiles.createTempFile(prefix, suffix, dir);
            if (!tmpDir.delete()) {
                throw new UncheckedIOException("Failed to delete file: " + tmpDir);
            }
            if (!tmpDir.mkdir()) {
                throw new UncheckedIOException("Failed to make directory: " + tmpDir);
            }
            return tmpDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

