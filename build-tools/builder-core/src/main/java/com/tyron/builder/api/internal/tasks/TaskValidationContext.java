package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.io.File;

public interface TaskValidationContext extends TypeValidationContext {

    FileOperations getFileOperations();

    boolean isInReservedFileSystemLocation(File location);
}
