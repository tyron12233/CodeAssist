package org.gradle.api.internal.tasks.properties;

import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.tasks.FileNormalizer;

public interface FilePropertySpec extends PropertySpec {
    Class<? extends FileNormalizer> getNormalizer();
    FileCollectionInternal getPropertyFiles();
}