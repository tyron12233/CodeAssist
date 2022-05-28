package com.tyron.builder.api.internal.provider.sources;

import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.provider.ValueSource;
import com.tyron.builder.api.provider.ValueSourceParameters;
import com.tyron.builder.api.tasks.InputFile;

import javax.annotation.Nullable;
import java.io.File;

public abstract class FileContentValueSource<T> implements ValueSource<T, FileContentValueSource.Parameters> {

    public interface Parameters extends ValueSourceParameters {

        @InputFile
        RegularFileProperty getFile();
    }

    @Nullable
    @Override
    public T obtain() {
        @Nullable final RegularFile regularFile = getParameters().getFile().getOrNull();
        if (regularFile == null) {
            return null;
        }
        final File file = regularFile.getAsFile();
        if (!file.isFile()) {
            return null;
        }
        return obtainFrom(file);
    }

    protected abstract T obtainFrom(File file);
}
