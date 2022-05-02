package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;

import org.jetbrains.annotations.Nullable;

public interface IProjectFactory {
    ProjectInternal createProject(GradleInternal gradle,
                                  ProjectDescriptor descriptor,
                                  ProjectStateUnk owner,
                                  @Nullable ProjectInternal parent,
                                  ClassLoaderScope selfClassLoaderScope,
                                  ClassLoaderScope baseClassLoaderScope);
}
