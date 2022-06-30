package com.tyron.builder.api.internal.tasks.properties;


import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.tasks.FileNormalizer;

public abstract class AbstractFilePropertySpec extends AbstractPropertySpec implements FilePropertySpec {
    private final Class<? extends FileNormalizer> normalizer;
    private final FileCollectionInternal files;

    public AbstractFilePropertySpec(String propertyName, Class<? extends FileNormalizer> normalizer, FileCollectionInternal files) {
        super(propertyName);
        this.normalizer = normalizer;
        this.files = files;
    }

    @Override
    public Class<? extends FileNormalizer> getNormalizer() {
        return normalizer;
    }

    @Override
    public FileCollectionInternal getPropertyFiles() {
        return files;
    }

    @Override
    public String toString() {
        return getPropertyName() + " (" + getNormalizer().getSimpleName().replace("Normalizer", "") + ")";
    }
}