package com.tyron.builder.internal.build;


import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.util.Path;

@ServiceScope(Scopes.BuildTree.class)
public interface IncludedBuildFactory {
    IncludedBuildState createBuild(BuildIdentifier buildIdentifier, Path identityPath, BuildDefinition buildDefinition, boolean isImplicit, BuildState owner);
}