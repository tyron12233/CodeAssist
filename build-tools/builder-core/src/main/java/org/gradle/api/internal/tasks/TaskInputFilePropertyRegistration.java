package org.gradle.api.internal.tasks;

import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.tasks.FileNormalizer;

public interface TaskInputFilePropertyRegistration extends TaskPropertyRegistration, TaskInputFilePropertyBuilderInternal {
    Class<? extends FileNormalizer> getNormalizer();
    InputFilePropertyType getFilePropertyType();
    boolean isSkipWhenEmpty();
    DirectorySensitivity getDirectorySensitivity();
    LineEndingSensitivity getLineEndingNormalization();
}