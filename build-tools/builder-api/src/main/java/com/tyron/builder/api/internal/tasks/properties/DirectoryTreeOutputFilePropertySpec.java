package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.fingerprint.OutputNormalizer;

import java.io.File;

/**
 * A (possibly filtered) directory tree which is used as an output.
 */
public class DirectoryTreeOutputFilePropertySpec extends AbstractFilePropertySpec implements OutputFilePropertySpec {
    private final File root;

    public DirectoryTreeOutputFilePropertySpec(String propertyName, FileCollectionInternal files, File root) {
        super(propertyName, OutputNormalizer.class, files);
        this.root = root;
    }

    @Override
    public TreeType getOutputType() {
        return TreeType.DIRECTORY;
    }

    @Override
    public File getOutputFile() {
        return root;
    }
}
