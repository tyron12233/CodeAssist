package com.tyron.builder.configurationcache;

import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.DefaultLocalComponentRegistry;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentInAnotherBuildProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentProvider;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.configuration.project.ProjectEvaluator;
import com.tyron.builder.execution.TaskSelector;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.model.CalculatedValueContainerFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.invocation.DefaultGradle;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.CrossProjectModelAccess;
import com.tyron.builder.api.internal.project.DefaultCrossProjectModelAccess;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.internal.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.configuration.ProjectsPreparer;
import com.tyron.builder.configuration.project.BuildScriptProcessor;
import com.tyron.builder.configuration.project.ConfigureActionsProjectEvaluator;
import com.tyron.builder.configuration.project.LifecycleProjectEvaluator;
import com.tyron.builder.execution.DefaultTaskSchedulingPreparer;
import com.tyron.builder.execution.ExcludedTaskFilteringProjectsPreparer;
import com.tyron.builder.initialization.SettingsPreparer;
import com.tyron.builder.initialization.TaskExecutionPreparer;
import com.tyron.builder.initialization.VintageBuildModelController;
import com.tyron.builder.initialization.exception.DefaultExceptionAnalyser;
import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.initialization.exception.MultipleBuildFailuresExceptionAnalyser;
import com.tyron.builder.initialization.exception.StackTraceSanitizingExceptionAnalyser;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildLifecycleControllerFactory;
import com.tyron.builder.internal.build.BuildModelController;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.model.StateTransitionControllerFactory;

import javax.annotation.Nullable;

@SuppressWarnings("unused")
public class DefaultBuildModelControllerServices implements BuildModelControllerServices {

    @Override
    public Supplier servicesForBuild(BuildDefinition buildDefinition,
                                     BuildState owner,
                                     @Nullable BuildState parentBuild) {
        return (registration, services) -> {
            registration.add(BuildDefinition.class, buildDefinition);
            registration.add(BuildState.class, owner);
            registration.addProvider(new ServicesProvider(buildDefinition, parentBuild, services));
            registration.addProvider(new VintageBuildControllerProvider());
            registration.addProvider(new VintageModelProvider());

            registration.addProvider(new VintageIsolatedProjectsProvider());

            registration.addProvider(new Object() {
                ExceptionAnalyser createExceptionAnalyser(ListenerManager listenerManager) {
                    return new StackTraceSanitizingExceptionAnalyser(
                            new MultipleBuildFailuresExceptionAnalyser(
                                    new DefaultExceptionAnalyser(listenerManager)
                            )
                    );
                }
            });
        };
    }

    private static class ServicesProvider {
        private final BuildDefinition buildDefinition;
        private final BuildState parentBuild;
        private final BuildScopeServices services;

        public ServicesProvider(BuildDefinition buildDefinition,
                                BuildState parentBuild,
                                BuildScopeServices services) {

            this.buildDefinition = buildDefinition;
            this.parentBuild = parentBuild;
            this.services = services;
        }

        GradleInternal createGradleModel(Instantiator instantiator, ServiceRegistryFactory registryFactory) {
            return instantiator.newInstance(DefaultGradle.class, parentBuild, buildDefinition.getStartParameter(), registryFactory);
        }

        BuildLifecycleController createBuildLifecycleController(BuildLifecycleControllerFactory factory) {
            return factory.newInstance(buildDefinition, services);
        }
    }

    private static class VintageBuildControllerProvider {
        BuildModelController createBuildModelController(
                GradleInternal gradle,
                StateTransitionControllerFactory factory
        ) {
            ProjectsPreparer projectsPreparer = gradle.getServices().get(ProjectsPreparer.class);
            DefaultTaskSchedulingPreparer taskSchedulingPreparer = new DefaultTaskSchedulingPreparer(new ExcludedTaskFilteringProjectsPreparer(
                    gradle.getServices().get(TaskSelector.class)));
            SettingsPreparer settingsPreparer = gradle.getServices().get(SettingsPreparer.class);
            TaskExecutionPreparer taskExecutionPreparer = gradle.getServices().get(TaskExecutionPreparer.class);
            return new VintageBuildModelController(
                    gradle,
                    projectsPreparer,
                    taskSchedulingPreparer,
                    settingsPreparer,
                    taskExecutionPreparer,
                    factory
            );
        }
    }

    private static class VintageModelProvider {
        public ProjectEvaluator createProjectEvaluator(
                BuildOperationExecutor buildOperationExecutor,
                ScriptPluginFactory configurerFactory,
                BuildCancellationToken buildCancellationToken
        ) {
            ConfigureActionsProjectEvaluator configure =
                    new ConfigureActionsProjectEvaluator(
                            new BuildScriptProcessor(configurerFactory)
                    );
            return new LifecycleProjectEvaluator(buildOperationExecutor, configure, buildCancellationToken);
        }

        public LocalComponentRegistry createLocalComponentRegistry(
                BuildState currentBuild,
                ProjectStateRegistry projectStateRegistry,
                CalculatedValueContainerFactory calculatedValueContainerFactory,
                LocalComponentProvider provider,
                LocalComponentInAnotherBuildProvider anotherBuildProvider
        ) {
            return new DefaultLocalComponentRegistry(
                    currentBuild.getBuildIdentifier(),
                    projectStateRegistry,
                    calculatedValueContainerFactory,
                    provider,
                    anotherBuildProvider
            );
        }
    }

    private static class VintageIsolatedProjectsProvider {
        protected CrossProjectModelAccess createCrossProjectModelAccess(
                ProjectRegistry<ProjectInternal> registry
        ) {
            return new DefaultCrossProjectModelAccess(registry);
        }
    }
}
