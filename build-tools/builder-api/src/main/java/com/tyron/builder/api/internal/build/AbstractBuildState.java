package com.tyron.builder.api.internal.build;

import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.util.Path;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public class AbstractBuildState implements BuildState, Closeable {
    @Override
    public DisplayName getDisplayName() {
        return null;
    }

    @Override
    public Path getIdentityPath() {
        return null;
    }

    @Override
    public boolean isImplicitBuild() {
        return false;
    }

    @Override
    public boolean isImportableBuild() {
        return false;
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
        return null;
    }

    @Override
    public Path calculateIdentityPathForProject(Path projectPath) throws IllegalStateException {
        return null;
    }

    @Override
    public void ensureProjectsLoaded() {

    }

    @Override
    public void ensureProjectsConfigured() {

    }

    @Override
    public BuildProjectRegistry getProjects() {
        return null;
    }

    @Override
    public File getBuildRootDir() {
        return null;
    }

    @Override
    public void close() throws IOException {

    }

//    private final BuildScopeServices buildServices;
//
//    public AbstractBuildState() {
//
//    }
//
//    protected BuildScopeServices prepareServices(BuildTreeState buildTree, BuildDefinition buildDefinition, BuildModelControllerServices.Supplier supplier) {
//        return new BuildScopeServices(buildTree.getServices(), supplier);
//    }
}
