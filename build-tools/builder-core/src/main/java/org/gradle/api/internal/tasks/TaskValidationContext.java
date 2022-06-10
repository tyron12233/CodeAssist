package org.gradle.api.internal.tasks;

import org.gradle.api.internal.file.FileOperations;
import org.gradle.internal.reflect.validation.TypeValidationContext;

import java.io.File;

public interface TaskValidationContext extends TypeValidationContext {

    FileOperations getFileOperations();

    boolean isInReservedFileSystemLocation(File location);
}
