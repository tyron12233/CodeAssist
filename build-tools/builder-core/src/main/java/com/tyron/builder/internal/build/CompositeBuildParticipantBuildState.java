package com.tyron.builder.internal.build;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import java.util.Set;

public interface CompositeBuildParticipantBuildState extends BuildState {
    /**
     * Returns the public view of a reference to this build.
     */
    IncludedBuildInternal getModel();

    /**
     * Returns the identities of the modules represented by the projects of this build. May configure the build model, if required.
     */
    Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> getAvailableModules();

    /**
     * Creates a copy of the identifier for a project in this build, to use in the dependency resolution result from some other build
     */
    ProjectComponentIdentifier idToReferenceProjectFromAnotherBuild(ProjectComponentIdentifier identifier);
}