package com.tyron.builder.initialization;

import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.initialization.dsl.VersionCatalogBuilder;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.classpath.ClassPath;

import java.util.List;

public interface DependenciesAccessors {
    void generateAccessors(List<VersionCatalogBuilder> builders, ClassLoaderScope classLoaderScope, Settings settings);
    void createExtensions(ProjectInternal project);
    ClassPath getSources();
    ClassPath getClasses();
}
