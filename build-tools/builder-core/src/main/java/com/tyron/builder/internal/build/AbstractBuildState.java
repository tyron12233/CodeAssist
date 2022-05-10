package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.internal.lazy.Lazy;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Function;

public abstract class AbstractBuildState implements BuildState, Closeable {

    private final BuildScopeServices buildServices;
    private final Lazy<BuildLifecycleController> buildLifecycleController;
    private final Lazy<ProjectStateRegistry> projectStateRegistry;
    private final Lazy<BuildWorkGraphController> workGraphController;

    public AbstractBuildState(BuildTreeState buildTree, BuildDefinition buildDefinition, @Nullable BuildState parent) {
        // Create the controllers using the services of the nested tree
        BuildModelControllerServices buildModelControllerServices = buildTree.getServices().get(BuildModelControllerServices.class);
        BuildModelControllerServices.Supplier supplier = buildModelControllerServices.servicesForBuild(buildDefinition, this, parent);
        buildServices = prepareServices(buildTree, buildDefinition, supplier);
        buildLifecycleController = Lazy.locking().of(() -> buildServices.get(BuildLifecycleController.class));
        projectStateRegistry = Lazy.locking().of(() -> buildServices.get(ProjectStateRegistry.class));
        workGraphController = Lazy.locking().of(() -> buildServices.get(BuildWorkGraphController.class));
    }

    protected BuildScopeServices prepareServices(BuildTreeState buildTree, BuildDefinition buildDefinition, BuildModelControllerServices.Supplier supplier) {
        return new BuildScopeServices(buildTree.getServices(), supplier);
    }

    protected BuildScopeServices getBuildServices() {
        return buildServices;
    }

    @Override
    public void close() throws IOException {
        buildServices.close();
    }

    @Override
    public DisplayName getDisplayName() {
        return Describables.of(getBuildIdentifier());
    }

    @Override
    public String toString() {
        return getDisplayName().getDisplayName();
    }

    @Override
    public void assertCanAdd(IncludedBuildSpec includedBuildSpec) {
        throw new UnsupportedOperationException("Cannot include build '" + includedBuildSpec.rootDir.getName() + "' in " + getBuildIdentifier() + ". This is not supported yet.");
    }

    @Override
    public boolean isImportableBuild() {
        return true;
    }

    protected ProjectStateRegistry getProjectStateRegistry() {
        return projectStateRegistry.get();
    }

    @Override
    public BuildProjectRegistry getProjects() {
        return getProjectStateRegistry().projectsFor(getBuildIdentifier());
    }

    protected BuildLifecycleController getBuildController() {
        return buildLifecycleController.get();
    }

    @Override
    public void ensureProjectsLoaded() {
        getBuildController().loadSettings();
    }

    @Override
    public void ensureProjectsConfigured() {
        getBuildController().configureProjects();
    }

    @Override
    public GradleInternal getMutableModel() {
        return getBuildController().getGradle();
    }

    @Override
    public BuildWorkGraphController getWorkGraph() {
        return workGraphController.get();
    }

    @Override
    public <T> T withToolingModels(Function<? super BuildToolingModelController, T> action) {
        return getBuildController().withToolingModels(action);
    }
}
