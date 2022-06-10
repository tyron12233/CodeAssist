package org.gradle.internal.build;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.internal.Pair;
import org.gradle.internal.composite.IncludedBuildInternal;

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