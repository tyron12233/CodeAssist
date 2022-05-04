package com.tyron.builder.api.internal.artifacts.dsl.dependencies;

import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.internal.project.ProjectInternal;

public class UnknownProjectFinder implements ProjectFinder {
    private final String exceptionMessage;

    public UnknownProjectFinder(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    @Override
    public ProjectInternal getProject(String path) {
        throw new UnknownProjectException(exceptionMessage);
    }
}
