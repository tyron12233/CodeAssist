package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.initialization.IncludedBuildSpec;

import java.util.Collection;

public interface BuildIncluder {
    IncludedBuildState includeBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle);

    void registerPluginBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle);

    Collection<IncludedBuildState> includeRegisteredPluginBuilds();
}
