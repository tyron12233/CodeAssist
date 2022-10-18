package org.gradle.api.internal.artifacts.dependencies;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;

public interface ProjectDependencyInternal extends ProjectDependency {
    Configuration findProjectConfiguration();
}
