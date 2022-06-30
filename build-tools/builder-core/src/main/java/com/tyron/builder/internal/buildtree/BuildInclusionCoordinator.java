package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Coordinates inclusion of builds across the build tree.
 */
@ServiceScope(Scopes.BuildTree.class)
public class BuildInclusionCoordinator {
    private final Set<IncludedBuildState> loadedBuilds = new CopyOnWriteArraySet<>();
    private final List<IncludedBuildState> libraryBuilds = new CopyOnWriteArrayList<>();
    private final BuildStateRegistry buildStateRegistry;

    public BuildInclusionCoordinator(BuildStateRegistry buildStateRegistry) {
        this.buildStateRegistry = buildStateRegistry;
    }

    public void prepareForInclusion(IncludedBuildState build, boolean asPlugin) {
        if (loadedBuilds.add(build)) {
            // Load projects (eg by running the settings script, etc) only the first time the build is included by another build.
            // This is to deal with cycles and the build being included multiple times in the tree
            build.ensureProjectsLoaded();
        }
        if (!asPlugin && !libraryBuilds.contains(build)) {
            libraryBuilds.add(build);
        }
    }

    public void registerGlobalLibrarySubstitutions() {
        for (IncludedBuildState includedBuild : libraryBuilds) {
            buildStateRegistry.registerSubstitutionsFor(includedBuild);
        }
    }
}