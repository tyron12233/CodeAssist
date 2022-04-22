package com.tyron.builder.internal.build;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.initialization.IncludedBuildSpec;

import java.util.Collection;

/**
 * Coordinates inclusion of builds from the current build.
 */
@ServiceScope(Scopes.Build.class)
public interface BuildIncluder {
    IncludedBuildState includeBuild(IncludedBuildSpec includedBuildSpec);

    void registerPluginBuild(IncludedBuildSpec includedBuildSpec);

    Collection<IncludedBuildState> includeRegisteredPluginBuilds();
}