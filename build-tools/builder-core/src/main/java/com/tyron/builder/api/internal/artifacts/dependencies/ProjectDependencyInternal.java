package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ProjectDependency;

public interface ProjectDependencyInternal extends ProjectDependency {
    Configuration findProjectConfiguration();
}
