package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.internal.file.ReservedFileSystemLocationRegistry;
import com.tyron.builder.internal.reflect.validation.PropertyProblemBuilder;
import com.tyron.builder.internal.reflect.validation.TypeProblemBuilder;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.io.File;

public class DefaultTaskValidationContext implements TaskValidationContext, TypeValidationContext {
    private final FileOperations fileOperations;
    private final ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry;
    private final TypeValidationContext delegate;

    public DefaultTaskValidationContext(FileOperations fileOperations, ReservedFileSystemLocationRegistry reservedFileSystemLocationRegistry, TypeValidationContext delegate) {
        this.fileOperations = fileOperations;
        this.reservedFileSystemLocationRegistry = reservedFileSystemLocationRegistry;
        this.delegate = delegate;
    }

    @Override
    public void visitTypeProblem(Action<? super TypeProblemBuilder> problemSpec) {
        delegate.visitTypeProblem(problemSpec);
    }

    @Override
    public void visitPropertyProblem(Action<? super PropertyProblemBuilder> problemSpec) {
        delegate.visitPropertyProblem(problemSpec);
    }

    @Override
    public FileOperations getFileOperations() {
        return fileOperations;
    }

    @Override
    public boolean isInReservedFileSystemLocation(File location) {
        return reservedFileSystemLocationRegistry.isInReservedFileSystemLocation(location);
    }
}
