package com.tyron.builder.api.internal.tasks.properties;


import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;

import org.jetbrains.annotations.Nullable;

public interface InputFilePropertySpec extends FilePropertySpec {
    boolean isSkipWhenEmpty();

    boolean isIncremental();

    DirectorySensitivity getDirectorySensitivity();

    LineEndingSensitivity getLineEndingNormalization();

    @Nullable
    Object getValue();
}