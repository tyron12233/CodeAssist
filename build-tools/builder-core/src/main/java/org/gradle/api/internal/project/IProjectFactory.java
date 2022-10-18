package org.gradle.api.internal.project;

import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;

import org.jetbrains.annotations.Nullable;

public interface IProjectFactory {
    ProjectInternal createProject(GradleInternal gradle,
                                  ProjectDescriptor descriptor,
                                  ProjectState owner,
                                  @Nullable ProjectInternal parent,
                                  ClassLoaderScope selfClassLoaderScope,
                                  ClassLoaderScope baseClassLoaderScope);
}
