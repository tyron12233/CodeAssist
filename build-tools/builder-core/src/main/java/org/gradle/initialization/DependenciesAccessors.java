package org.gradle.initialization;

import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.dsl.VersionCatalogBuilder;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.classpath.ClassPath;

import java.util.List;

public interface DependenciesAccessors {
    void generateAccessors(List<VersionCatalogBuilder> builders, ClassLoaderScope classLoaderScope, Settings settings);
    void createExtensions(ProjectInternal project);
    ClassPath getSources();
    ClassPath getClasses();
}
