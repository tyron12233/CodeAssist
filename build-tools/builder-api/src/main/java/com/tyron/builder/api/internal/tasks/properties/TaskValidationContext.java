package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

import java.io.File;

public interface TaskValidationContext extends TypeValidationContext {

    FileOperations getFileOperations();

    boolean isInReservedFileSystemLocation(File location);
}
