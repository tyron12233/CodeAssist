package com.tyron.builder.api.internal.project;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Mediates access across project boundaries.
 */
@ServiceScope(Scopes.Build.class)
public interface CrossProjectModelAccess {
    /**
     * Locates the given project relative to some project.
     *
     * @param referrer The project from which the return value will be used.
     * @param path absolute path
     */
    @Nullable
    ProjectInternal findProject(ProjectInternal referrer, ProjectInternal relativeTo, String path);

    /**
     * @param referrer The project from which the return value will be used.
     */
    ProjectInternal access(ProjectInternal referrer, ProjectInternal project);

    /**
     * @param referrer The project from which the return value will be used.
     */
    Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer, ProjectInternal relativeTo);

    /**
     * @param referrer The project from which the return value will be used.
     */
    Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer, ProjectInternal relativeTo);
}

