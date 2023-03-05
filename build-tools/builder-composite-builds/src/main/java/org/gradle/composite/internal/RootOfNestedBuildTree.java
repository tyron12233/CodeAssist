package org.gradle.composite.internal;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.util.Path;
import org.gradle.initialization.RunNestedBuildBuildOperationType;
import org.gradle.initialization.exception.ExceptionAnalyser;
import org.gradle.initialization.layout.BuildLayout;
import org.gradle.internal.InternalBuildAdapter;
import org.gradle.internal.Pair;
import org.gradle.internal.build.AbstractBuildState;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.NestedRootBuild;
import org.gradle.internal.buildtree.BuildTreeFinishExecutor;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.buildtree.BuildTreeLifecycleControllerFactory;
import org.gradle.internal.buildtree.BuildTreeState;
import org.gradle.internal.buildtree.BuildTreeWorkExecutor;
import org.gradle.internal.buildtree.DefaultBuildTreeFinishExecutor;
import org.gradle.internal.buildtree.DefaultBuildTreeWorkExecutor;
import org.gradle.internal.composite.IncludedBuildInternal;

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