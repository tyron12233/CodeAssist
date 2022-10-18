package org.gradle.api.internal.tasks.properties;

import org.gradle.internal.file.TreeType;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface OutputFilePropertySpec extends FilePropertySpec {
    TreeType getOutputType();
    @Nullable
    File getOutputFile();
}
