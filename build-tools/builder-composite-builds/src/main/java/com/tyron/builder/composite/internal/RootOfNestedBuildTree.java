package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.util.Path;
import com.tyron.builder.initialization.RunNestedBuildBuildOperationType;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.layout.BuildLayout;
import com.tyron.builder.internal.InternalBuildAdapter;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.build.AbstractBuildState;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.NestedRootBuild;
import com.tyron.builder.internal.buildtree.BuildTreeFinishExecutor;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleControllerFactory;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.buildtree.BuildTreeWorkExecutor;
import com.tyron.builder.internal.buildtree.DefaultBuildTreeFinishExecutor;
import com.tyron.builder.internal.buildtree.DefaultBuildTreeWorkExecutor;
import com.tyron.builder.internal.composite.IncludedBuildInternal;

import java.io.File;
import java.util.Set;
import java.util.function.Function;

public class RootOfNestedBuildTree extends AbstractBuildState implements NestedRootBuild {
    private final BuildIdentifier buildIdentifier;
    private final Path identityPath;
    private final BuildState owner;
    private final BuildTreeLifecycleController buildTreeLifecycleController;
    private String buildName;

    public RootOfNestedBuildTree(
            BuildDefinition buildDefinition,
            BuildIdentifier buildIdentifier,
            Path identityPath,
            BuildState owner,
            BuildTreeState buildTree
    ) {
        super(buildTree, buildDefinition, owner);
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.owner = owner;
        this.buildName = buildDefinition.getName() == null ? buildIdentifier.getName() : buildDefinition.getName();

        BuildScopeServices buildServices = getBuildServices();
        BuildLifecycleController buildLifecycleController = getBuildController();
        BuildTreeLifecycleControllerFactory buildTreeLifecycleControllerFactory = buildServices.get(BuildTreeLifecycleControllerFactory.class);
        ExceptionAnalyser exceptionAnalyser = buildServices.get(ExceptionAnalyser.class);
        BuildStateRegistry buildStateRegistry = buildServices.get(BuildStateRegistry.class);
        BuildTreeWorkExecutor buildTreeWorkExecutor = new DefaultBuildTreeWorkExecutor();
        BuildTreeFinishExecutor buildTreeFinishExecutor = new DefaultBuildTreeFinishExecutor(buildStateRegistry, exceptionAnalyser, buildLifecycleController);
        buildTreeLifecycleController = buildTreeLifecycleControllerFactory.createController(buildLifecycleController, buildTreeWorkExecutor, buildTreeFinishExecutor);
    }

    public void attach() {
        getBuildServices().get(BuildStateRegistry.class).attachRootBuild(this);
    }

    @Override
    public StartParameterInternal getStartParameter() {
        return getBuildController().getGradle().getStartParameter();
    }

    @Override
    public BuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public boolean isImplicitBuild() {
        return false;
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
        return owner.getCurrentPrefixForProjectsInChildBuilds().child(buildName);
    }

    @Override
    public Path calculateIdentityPathForProject(Path projectPath) {
        return getBuildController().getGradle().getIdentityPath().append(projectPath);
    }

    @Override
    public File getBuildRootDir() {
        return getBuildController().getGradle().getServices().get(BuildLayout.class).getRootDirectory();
    }

    @Override
    public IncludedBuildInternal getModel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> getAvailableModules() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProjectComponentIdentifier idToReferenceProjectFromAnotherBuild(ProjectComponentIdentifier identifier) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T run(Function<? super BuildTreeLifecycleController, T> action) {
        final GradleInternal gradle = getBuildController().getGradle();
        ServiceRegistry services = gradle.getServices();
        BuildOperationExecutor executor = services.get(BuildOperationExecutor.class);
        return executor.call(new CallableBuildOperation<T>() {
            @Override
            public T call(BuildOperationContext context) {
                gradle.addBuildListener(new InternalBuildAdapter() {
                    @Override
                    public void settingsEvaluated(Settings settings) {
                        buildName = settings.getRootProject().getName();
                    }
                });
                T result = action.apply(buildTreeLifecycleController);
                context.setResult(new RunNestedBuildBuildOperationType.Result() {
                });
                return result;
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Run nested build")
                        .details((RunNestedBuildBuildOperationType.Details) () -> gradle.getIdentityPath().getPath());
            }
        });
    }
}