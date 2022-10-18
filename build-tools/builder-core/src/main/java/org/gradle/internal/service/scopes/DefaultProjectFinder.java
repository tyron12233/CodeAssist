package org.gradle.internal.service.scopes;

import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.project.ProjectInternal;

import java.util.function.Supplier;

public class DefaultProjectFinder implements ProjectFinder {
    private final Supplier<ProjectInternal> baseProjectSupplier;

    public DefaultProjectFinder(Supplier<ProjectInternal> baseProjectSupplier) {
        this.baseProjectSupplier = baseProjectSupplier;
    }

    @Override
    public ProjectInternal getProject(String path) {
        return baseProjectSupplier.get().project(path);
    }
}
