package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.internal.tasks.PropertyFileCollection;
import com.tyron.builder.api.tasks.FileNormalizer;

import org.jetbrains.annotations.Nullable;


public class DefaultInputFilePropertySpec extends AbstractFilePropertySpec implements InputFilePropertySpec {
    private final boolean skipWhenEmpty;
    private final boolean incremental;
    private final DirectorySensitivity directorySensitivity;
    private final LineEndingSensitivity lineEndingSensitivity;
    private final PropertyValue value;

    public DefaultInputFilePropertySpec(
            String propertyName,
            Class<? extends FileNormalizer> normalizer,
            FileCollectionInternal files,
            PropertyValue value,
            boolean skipWhenEmpty,
            boolean incremental,
            DirectorySensitivity directorySensitivity,
            LineEndingSensitivity lineEndingSensitivity
    ) {
        super(propertyName, normalizer, files);
        this.skipWhenEmpty = skipWhenEmpty;
        this.incremental = incremental;
        this.directorySensitivity = directorySensitivity;
        this.lineEndingSensitivity = lineEndingSensitivity;
        this.value = value;
    }

    @Override
    public boolean isSkipWhenEmpty() {
        return skipWhenEmpty;
    }

    @Override
    public boolean isIncremental() {
        return incremental;
    }

    @Override
    public DirectorySensitivity getDirectorySensitivity() {
        return directorySensitivity;
    }

    @Override
    public LineEndingSensitivity getLineEndingNormalization() {
        return lineEndingSensitivity;
    }

    @Override
    @Nullable
    public Object getValue() {
        return value.getUnprocessedValue();
    }
}