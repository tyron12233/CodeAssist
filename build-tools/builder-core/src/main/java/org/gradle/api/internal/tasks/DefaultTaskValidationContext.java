package org.gradle.api.internal.tasks;

import org.gradle.api.Action;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.internal.file.ReservedFileSystemLocationRegistry;
import org.gradle.internal.reflect.validation.PropertyProblemBuilder;
import org.gradle.internal.reflect.validation.TypeProblemBuilder;
import org.gradle.internal.reflect.validation.TypeValidationContext;

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
