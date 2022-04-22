package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.tasks.FileNormalizer;

public interface TaskInputFilePropertyRegistration extends TaskPropertyRegistration, TaskInputFilePropertyBuilderInternal {
    Class<? extends FileNormalizer> getNormalizer();
    InputFilePropertyType getFilePropertyType();
    boolean isSkipWhenEmpty();
    DirectorySensitivity getDirectorySensitivity();
    LineEndingSensitivity getLineEndingNormalization();
}