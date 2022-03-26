package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.tasks.FileNormalizer;

public interface FilePropertySpec extends PropertySpec {
    Class<? extends FileNormalizer> getNormalizer();
    FileCollectionInternal getPropertyFiles();
}