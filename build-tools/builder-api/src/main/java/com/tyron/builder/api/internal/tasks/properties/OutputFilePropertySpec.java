package com.tyron.builder.api.internal.tasks.properties;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface OutputFilePropertySpec extends FilePropertySpec {
    TreeType getOutputType();
    @Nullable
    File getOutputFile();
}
