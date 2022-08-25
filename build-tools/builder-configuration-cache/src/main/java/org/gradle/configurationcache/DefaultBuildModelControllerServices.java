package org.gradle.configurationcache;

import org.gradle.api.internal.artifacts.ivyservice.projectmodule.DefaultLocalComponentRegistry;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentInAnotherBuildProvider;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentProvider;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectArtifactResolver;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectArtifactSetResolver;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.configuration.project.ProjectEvaluator;
import org.gradle.execution.TaskSelector;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.invocation.DefaultGradle;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.api.internal.project.CrossProjectModelAccess;
import org.gradle.api.internal.project.DefaultCrossProjectModelAccess;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.internal.service.scopes.ServiceRegistryFactory;
import org.gradle.configuration.ProjectsPreparer;
import org.gradle.configuration.project.BuildScriptProcessor;
import org.gradle.configuration.project.ConfigureActionsProjectEvaluator;
import org.gradle.configuration.project.LifecycleProjectEvaluator;
import org.gradle.execution.DefaultTaskSchedulingPreparer;
import org.gradle.execution.ExcludedTaskFilteringProjectsPreparer;
import org.gradle.initialization.SettingsPreparer;
import org.gradle.initialization.TaskExecutionPreparer;
import org.gradle.initialization.VintageBuildModelController;
import org.gradle.initialization.exception.DefaultExceptionAnalyser;
import org.gradle.initialization.exception.ExceptionAnalyser;
import org.gradle.initialization.exception.MultipleBuildFailuresExceptionAnalyser;
import org.gradle.initialization.exception.StackTraceSanitizingExceptionAnalyser;
import org.gradle.internal.build.BuildLifecycleController;
import org.gradle.internal.build.BuildLifecycleControllerFactory;
import org.gradle.internal.build.BuildModelController;
import org.gradle.internal.build.BuildModelControllerServices;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.model.StateTransitionControllerFactory;

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
                LocalComponentInAnotherBuildProvider anotherBuildProvider,
                ProjectArtifactSetResolver artifactResolver
        ) {
            return new DefaultLocalComponentRegistry(
                    currentBuild.getBuildIdentifier(),
                    projectStateRegistry,
                    calculatedValueContainerFactory,
                    provider,
                    anotherBuildProvider,
                    artifactResolver
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
