package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileCollectionInternal;

public interface FilePropertySpec extends PropertySpec {
//    Class<? extends FileNormalizer> getNormalizer();
    FileCollectionInternal getPropertyFiles();
}