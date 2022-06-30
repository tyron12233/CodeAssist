package com.tyron.builder.composite.internal;

import static com.tyron.builder.api.internal.SettingsInternal.BUILD_SRC;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.plugin.management.internal.PluginRequests;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.NestedBuildTree;
import com.tyron.builder.internal.build.RootBuildState;
import com.tyron.builder.internal.build.StandAloneNestedBuild;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;

import java.io.File;

@ServiceScope(Scopes.BuildTree.class)
public class BuildStateFactory {
    private final BuildTreeState buildTreeState;
    private final ListenerManager listenerManager;
    private final GradleUserHomeScopeServiceRegistry userHomeDirServiceRegistry;
    private final CrossBuildSessionState crossBuildSessionState;
    private final BuildCancellationToken buildCancellationToken;

    public BuildStateFactory(
            BuildTreeState buildTreeState,
            ListenerManager listenerManager,
            GradleUserHomeScopeServiceRegistry userHomeDirServiceRegistry,
            CrossBuildSessionState crossBuildSessionState,
            BuildCancellationToken buildCancellationToken
    ) {
        this.buildTreeState = buildTreeState;
        this.listenerManager = listenerManager;
        this.userHomeDirServiceRegistry = userHomeDirServiceRegistry;
        this.crossBuildSessionState = crossBuildSessionState;
        this.buildCancellationToken = buildCancellationToken;
    }

    public RootBuildState createRootBuild(BuildDefinition buildDefinition) {
        return new DefaultRootBuildState(buildDefinition, buildTreeState, listenerManager);
    }

    public StandAloneNestedBuild createNestedBuild(BuildIdentifier buildIdentifier, Path identityPath, BuildDefinition buildDefinition, BuildState owner) {
        DefaultNestedBuild build = new DefaultNestedBuild(buildIdentifier, identityPath, buildDefinition, owner, buildTreeState);
        // Expose any contributions from the parent's settings
        build.getMutableModel().setClassLoaderScope(() -> owner.getMutableModel().getSettings().getClassLoaderScope());
        return build;
    }

    public NestedBuildTree createNestedTree(
            BuildDefinition buildDefinition,
            BuildIdentifier buildIdentifier,
            Path identityPath,
            BuildState owner
    ) {
        return new DefaultNestedBuildTree(buildDefinition, buildIdentifier, identityPath, owner, userHomeDirServiceRegistry, crossBuildSessionState, buildCancellationToken);
    }

    public BuildDefinition buildDefinitionFor(File buildSrcDir, BuildState owner) {
        PublicBuildPath publicBuildPath = owner.getMutableModel().getServices().get(PublicBuildPath.class);
        StartParameterInternal buildSrcStartParameter = buildSrcStartParameterFor(buildSrcDir, owner.getMutableModel().getStartParameter());
        BuildDefinition buildDefinition = BuildDefinition.fromStartParameterForBuild(
                buildSrcStartParameter,
                BUILD_SRC,
                buildSrcDir,
                PluginRequests.EMPTY,
                Actions.doNothing(),
                publicBuildPath,
                true
        );
        File customBuildFile = buildSrcStartParameter.getBuildFile();
        assert customBuildFile == null;
        return buildDefinition;
    }

    private StartParameterInternal buildSrcStartParameterFor(File buildSrcDir, StartParameter containingBuildParameters) {
        final StartParameterInternal buildSrcStartParameter = (StartParameterInternal) containingBuildParameters.newBuild();
        buildSrcStartParameter.setCurrentDir(buildSrcDir);
        buildSrcStartParameter.setProjectProperties(containingBuildParameters.getProjectProperties());
        buildSrcStartParameter.doNotSearchUpwards();
//        buildSrcStartParameter.setProfile(containingBuildParameters.isProfile());
        return buildSrcStartParameter;
    }
}
