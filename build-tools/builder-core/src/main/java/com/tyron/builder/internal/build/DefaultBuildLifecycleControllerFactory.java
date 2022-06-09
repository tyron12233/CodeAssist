package com.tyron.builder.internal.build;


import com.tyron.builder.StartParameter;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.initialization.BuildCompletionListener;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.internal.InternalBuildFinishedListener;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.featurelifecycle.LoggingDeprecatedFeatureHandler;
import com.tyron.builder.internal.featurelifecycle.ScriptUsageLocationReporter;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;
import com.tyron.builder.internal.operations.BuildOperationProgressEventEmitter;
import com.tyron.builder.internal.service.scopes.BuildScopeListenerManagerAction;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;

import java.io.File;

public class DefaultBuildLifecycleControllerFactory implements BuildLifecycleControllerFactory {
    private final StateTransitionControllerFactory stateTransitionControllerFactory;
    private final BuildToolingModelControllerFactory buildToolingModelControllerFactory;
    private final ExceptionAnalyser exceptionAnalyser;

    public DefaultBuildLifecycleControllerFactory(
            StateTransitionControllerFactory stateTransitionControllerFactory,
            BuildToolingModelControllerFactory buildToolingModelControllerFactory,
            ExceptionAnalyser exceptionAnalyser
    ) {
        this.stateTransitionControllerFactory = stateTransitionControllerFactory;
        this.buildToolingModelControllerFactory = buildToolingModelControllerFactory;
        this.exceptionAnalyser = exceptionAnalyser;
    }

    @Override
    public BuildLifecycleController newInstance(BuildDefinition buildDefinition, BuildScopeServices buildScopeServices) {
        StartParameter startParameter = buildDefinition.getStartParameter();

        final ListenerManager listenerManager = buildScopeServices.get(ListenerManager.class);
        for (Action<ListenerManager> action : buildScopeServices.getAll(BuildScopeListenerManagerAction.class)) {
            action.execute(listenerManager);
        }

        ScriptUsageLocationReporter usageLocationReporter = new ScriptUsageLocationReporter();
        listenerManager.addListener(usageLocationReporter);

        ShowStacktrace showStacktrace = startParameter.getShowStacktrace();
        switch (showStacktrace) {
            case ALWAYS:
            case ALWAYS_FULL:
                LoggingDeprecatedFeatureHandler.setTraceLoggingEnabled(true);
                break;
            default:
                LoggingDeprecatedFeatureHandler.setTraceLoggingEnabled(false);
        }

        DeprecationLogger.init(usageLocationReporter, startParameter.getWarningMode(), buildScopeServices.get(BuildOperationProgressEventEmitter.class));
        @SuppressWarnings("deprecation")
        File customSettingsFile = startParameter.getSettingsFile();
        if (customSettingsFile != null) {
            DeprecationLogger.deprecateAction("Specifying custom settings file location")
                    .willBeRemovedInGradle8()
                    .withUpgradeGuideSection(7, "configuring_custom_build_layout")
                    .nagUser();
        }
        @SuppressWarnings("deprecation")
        File customBuildFile = startParameter.getBuildFile();
        if (customBuildFile != null) {
            DeprecationLogger.deprecateAction("Specifying custom build file location")
                    .willBeRemovedInGradle8()
                    .withUpgradeGuideSection(7, "configuring_custom_build_layout")
                    .nagUser();
        }

        GradleInternal gradle = buildScopeServices.get(GradleInternal.class);

        BuildModelController buildModelController = buildScopeServices.get(BuildModelController.class);

        return new DefaultBuildLifecycleController(
                gradle,
                buildModelController,
                exceptionAnalyser,
                gradle.getBuildListenerBroadcaster(),
                listenerManager.getBroadcaster(BuildCompletionListener.class),
                listenerManager.getBroadcaster(InternalBuildFinishedListener.class),
                gradle.getServices().get(BuildWorkPreparer.class),
                gradle.getServices().get(BuildWorkExecutor.class),
                buildScopeServices,
                buildToolingModelControllerFactory,
                stateTransitionControllerFactory
        );
    }
}

