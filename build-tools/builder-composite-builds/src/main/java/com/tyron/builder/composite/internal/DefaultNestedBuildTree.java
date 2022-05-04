package com.tyron.builder.composite.internal;

import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.util.Path;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.initialization.DefaultBuildRequestMetaData;
import com.tyron.builder.initialization.NoOpBuildEventConsumer;
import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.NestedBuildTree;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildtree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionState;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;

import java.util.function.Function;

public class DefaultNestedBuildTree implements NestedBuildTree {
    private final BuildDefinition buildDefinition;
    private final BuildIdentifier buildIdentifier;
    private final Path identityPath;
    private final BuildState owner;
    private final GradleUserHomeScopeServiceRegistry userHomeDirServiceRegistry;
    private final CrossBuildSessionState crossBuildSessionState;
    private final BuildCancellationToken buildCancellationToken;

    public DefaultNestedBuildTree(
            BuildDefinition buildDefinition,
            BuildIdentifier buildIdentifier,
            Path identityPath,
            BuildState owner,
            GradleUserHomeScopeServiceRegistry userHomeDirServiceRegistry,
            CrossBuildSessionState crossBuildSessionState,
            BuildCancellationToken buildCancellationToken
    ) {
        this.buildDefinition = buildDefinition;
        this.buildIdentifier = buildIdentifier;
        this.identityPath = identityPath;
        this.owner = owner;
        this.userHomeDirServiceRegistry = userHomeDirServiceRegistry;
        this.crossBuildSessionState = crossBuildSessionState;
        this.buildCancellationToken = buildCancellationToken;
    }

    @Override
    public <T> T run(Function<? super BuildTreeLifecycleController, T> buildAction) {
        StartParameterInternal startParameter = buildDefinition.getStartParameter();
        BuildRequestMetaData buildRequestMetaData = new DefaultBuildRequestMetaData(Time.currentTimeMillis());
        try (BuildSessionState session = new BuildSessionState(userHomeDirServiceRegistry,
                crossBuildSessionState, startParameter, buildRequestMetaData, ClassPath.EMPTY,
                buildCancellationToken, buildRequestMetaData.getClient(),
                new NoOpBuildEventConsumer())) {
            session.getServices().get(BuildLayoutValidator.class).validate(startParameter);
            BuildTreeModelControllerServices.Supplier modelServices =
                    session.getServices().get(BuildTreeModelControllerServices.class)
                            .servicesForNestedBuildTree(startParameter);
            try (BuildTreeState buildTree = new BuildTreeState(session.getServices(),
                    modelServices)) {
                RootOfNestedBuildTree rootBuild =
                        new RootOfNestedBuildTree(buildDefinition, buildIdentifier, identityPath,
                                owner, buildTree);
                rootBuild.attach();
                return rootBuild.run(buildAction);
            }
        }
    }
}