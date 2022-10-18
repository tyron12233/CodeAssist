package org.gradle.api.internal.artifacts.dsl.dependencies;

import org.gradle.api.UnknownProjectException;
import org.gradle.api.internal.project.ProjectInternal;

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
