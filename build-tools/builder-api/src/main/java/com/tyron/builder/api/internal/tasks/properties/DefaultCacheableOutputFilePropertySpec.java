package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.fingerprint.OutputNormalizer;

import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * An output property consisting of a single output file/directory.
 *
 * When using directory trees as outputs (e.g. via {@link org.gradle.api.Project#fileTree(Object)}), {@link DirectoryTreeOutputFilePropertySpec} is used.
 * Everything else will use this class.
 */
public class DefaultCacheableOutputFilePropertySpec extends AbstractFilePropertySpec implements CacheableOutputFilePropertySpec {
    private final String propertySuffix;
    private final TreeType outputType;

    public DefaultCacheableOutputFilePropertySpec(
            String propertyName,
            @Nullable String propertySuffix,
            FileCollectionInternal outputFiles,
            TreeType outputType
    ) {
        super(propertyName, OutputNormalizer.class, outputFiles);
        this.propertySuffix = propertySuffix;
        this.outputType = outputType;
    }

    @Override
    public String getPropertyName() {
        return propertySuffix != null ? super.getPropertyName() + propertySuffix : super.getPropertyName();
    }

    @Override
    public File getOutputFile() {
        return getPropertyFiles().getSingleFile();
    }

    @Override
    public TreeType getOutputType() {
        return outputType;
    }
}