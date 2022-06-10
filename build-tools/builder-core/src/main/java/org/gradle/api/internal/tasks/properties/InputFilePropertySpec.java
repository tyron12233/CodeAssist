package org.gradle.api.internal.tasks.properties;


import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.LineEndingSensitivity;

import org.jetbrains.annotations.Nullable;

public interface InputFilePropertySpec extends FilePropertySpec {
    boolean isSkipWhenEmpty();

    boolean isIncremental();

    DirectorySensitivity getDirectorySensitivity();

    LineEndingSensitivity getLineEndingNormalization();

    @Nullable
    Object getValue();
}