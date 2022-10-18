package org.gradle.api.internal.provider.sources;

import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.api.tasks.InputFile;

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
