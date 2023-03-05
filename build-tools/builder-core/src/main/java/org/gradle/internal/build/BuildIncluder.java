package org.gradle.internal.build;

import org.gradle.api.internal.GradleInternal;
import org.gradle.initialization.IncludedBuildSpec;

import java.util.Collection;

public interface BuildIncluder {
    IncludedBuildState includeBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle);

    void registerPluginBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle);

    Collection<IncludedBuildState> includeRegisteredPluginBuilds();
}
