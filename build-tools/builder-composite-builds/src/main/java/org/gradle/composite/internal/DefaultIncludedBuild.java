package org.gradle.composite.internal;

import com.google.common.base.Preconditions;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.DependencySubstitutions;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.api.tasks.TaskReference;
import org.gradle.util.Path;
import org.gradle.initialization.IncludedBuildSpec;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.ExecutionResult;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.buildtree.BuildTreeState;
import org.gradle.internal.composite.IncludedBuildInternal;

import java.io.File;

public class DefaultIncludedBuild extends AbstractCompositeParticipantBuildState implements IncludedBuildState {
    private final BuildIdentifier buildIdentifier;
    private final Path identityPath;
    private final BuildDefinition buildDefinition;
    private final boolean isImplicit;
    private final BuildState owner;
    private final IncludedBuildImpl model;

    public DefaultIncludedBuild(
            BuildIdentifier buildIdentifier,
            Path identityPath,
            BuildDefinition buildDefinition,
            boolean isImplicit,
            BuildState owner,
            BuildTreeState buildTree,
            Instantiator instantiator
    ) {
        // Use a defensive copy of the build definition, as it may be mutated during build execution
        super(buildTree, buildDefinition.newInstance(), owner);
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.buildDefinition = buildDefinition;
        this.isImplicit = isImplicit;
        this.owner = owner;
        this.model = instantiator.newInstance(IncludedBuildImpl.class, this);
    }

    @Override
    public BuildIdentifier getBuildIdentifier() {
        return buildIdentifier;
    }

    @Override
    public File getRootDirectory() {
        return buildDefinition.getBuildRootDir();
    }

    @Override
    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public boolean isImplicitBuild() {
        return isImplicit;
    }

    @Override
    public boolean isImportableBuild() {
        return !isImplicit;
    }

    @Override
    public IncludedBuildInternal getModel() {
        return model;
    }

    @Override
    public boolean isPluginBuild() {
        return buildDefinition.isPluginBuild();
    }

    File getProjectDir() {
        return buildDefinition.getBuildRootDir();
    }

    @Override
    public String getName() {
        return identityPath.getName();
    }

    @Override
    public void assertCanAdd(IncludedBuildSpec includedBuildSpec) {
        if (isImplicit) {
            // Not yet supported for implicit included builds
            super.assertCanAdd(includedBuildSpec);
        }
    }

    @Override
    public File getBuildRootDir() {
        return buildDefinition.getBuildRootDir();
    }

    @Override
    public Path getCurrentPrefixForProjectsInChildBuilds() {
        return owner.getCurrentPrefixForProjectsInChildBuilds().child(buildIdentifier.getName());
    }

    @Override
    public Path calculateIdentityPathForProject(Path projectPath) {
        return getIdentityPath().append(projectPath);
    }

    @Override
    public Action<? super DependencySubstitutions> getRegisteredDependencySubstitutions() {
        return buildDefinition.getDependencySubstitutions();
    }

    @Override
    public <T> T withState(Transformer<T, ? super GradleInternal> action) {
        // This should apply some locking, but most access to the build state does not happen via this method yet
        return action.transform(getMutableModel());
    }

    @Override
    public ExecutionResult<Void> finishBuild() {
        return getBuildController().finishBuild(null);
    }

    public static class IncludedBuildImpl implements IncludedBuildInternal {
        private final DefaultIncludedBuild buildState;

        public IncludedBuildImpl(DefaultIncludedBuild buildState) {
            this.buildState = buildState;
        }

        @Override
        public String getName() {
            return buildState.getName();
        }

        @Override
        public File getProjectDir() {
            return buildState.getProjectDir();
        }

        @Override
        public TaskReference task(String path) {
            Preconditions.checkArgument(path.startsWith(":"), "Task path '%s' is not a qualified task path (e.g. ':task' or ':project:task').", path);
            return new IncludedBuildTaskReference(buildState, path);
        }

        @Override
        public BuildState getTarget() {
            return buildState;
        }
    }
}