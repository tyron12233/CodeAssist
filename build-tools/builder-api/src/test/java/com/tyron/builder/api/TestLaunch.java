package com.tyron.builder.api;

import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.invocation.BuildAction;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistryBuilder;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GlobalServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GradleUserHomeScopeServices;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.project.ProjectBuilderImpl;
import com.tyron.builder.composite.internal.BuildStateFactory;
import com.tyron.builder.composite.internal.DefaultIncludedBuildFactory;
import com.tyron.builder.composite.internal.DefaultIncludedBuildRegistry;
import com.tyron.builder.composite.internal.IncludedBuildDependencySubstitutionsBuilder;
import com.tyron.builder.configuration.GradleLauncherMetaData;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.internal.BuildType;
import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildFactory;
import com.tyron.builder.internal.buildTree.BuildActionModelRequirements;
import com.tyron.builder.internal.buildTree.BuildActionRunner;
import com.tyron.builder.internal.buildTree.BuildModelParameters;
import com.tyron.builder.internal.buildTree.BuildTreeActionExecutor;
import com.tyron.builder.internal.buildTree.BuildTreeContext;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildTree.BuildTreeState;
import com.tyron.builder.internal.buildTree.RunTasksRequirements;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionContext;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;
import com.tyron.builder.launcher.exec.RootBuildLifecycleBuildActionExecutor;
import com.tyron.common.TestUtil;

import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.File;
import java.util.Collections;

public class TestLaunch {

    private final BuildTreeModelControllerServices buildTreeModelControllerServices;
    private final  BuildLayoutValidator validator;

    public TestLaunch() {
        validator = new BuildLayoutValidator(
                new BuildLayoutFactory(),
                new DocumentationRegistry(),
                new GradleLauncherMetaData(),
                Collections.emptyList()
        );
        buildTreeModelControllerServices = new BuildTreeModelControllerServices() {
            @Override
            public Supplier servicesForBuildTree(BuildActionModelRequirements requirements) {

                BuildModelParameters modelParameters = new BuildModelParameters(
                        true,
                        true,
                        false,
                        true,
                        true,
                        false,
                        true
                );
                return registration -> {
                    registration.add(BuildType.class, BuildType.TASKS);
                    registerServices(registration, modelParameters, requirements);
                };
            }

            private void registerServices(ServiceRegistration registration,
                                          BuildModelParameters modelParameters,
                                          BuildActionModelRequirements requirements) {
                registration.add(BuildModelParameters.class, modelParameters);
                registration.add(BuildActionModelRequirements.class, requirements);
            }

            @Override
            public Supplier servicesForNestedBuildTree(StartParameterInternal startParameter) {
                return null;
            }
        };
    }

    @Test
    public void testProjectBuilder() {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        File gradleUserHomeDir = new File(resourcesDirectory, ".gradle");
        File testProjectDir = new File(resourcesDirectory, "TestProject");

        ProjectBuilderImpl projectBuilder = new ProjectBuilderImpl();
        ProjectInternal testProject =
                projectBuilder.createProject("TestProject", testProjectDir, gradleUserHomeDir);

        System.out.println(testProject);
    }

    @Test
    public void test() {
        StartParameterInternal startParameter = new StartParameterInternal();
        BuildAction buildAction = new BuildAction() {
            @Override
            public StartParameterInternal getStartParameter() {
                return startParameter;
            }

            @Override
            public boolean isRunTasks() {
                return false;
            }

            @Override
            public boolean isCreateModel() {
                return true;
            }
        };

//        build(buildAction, new BuildSessionContext() {
//            @Override
//            public ServiceRegistry getServices() {
//                GlobalServices global = new GlobalServices();
//                GradleUserHomeScopeServices userHomeScopeServices = new GradleUserHomeScopeServices(global);
//                BuildScopeServices buildScopeServices = new BuildScopeServices(userHomeScopeServices, null);
//                BuildTreeState buildTreeState = new BuildTreeState(buildScopeServices, registration -> {
//
//                        });
//                CrossBuildSessionState crossBuildSessionState = new CrossBuildSessionState(buildScopeServices, startParameter);
//                return ServiceRegistryBuilder.builder()
//                        .parent(buildScopeServices)
//                        .provider(new Object() {
//
//                            BuildModelControllerServices createBuildModelControllerServices() {
//                                return new BuildModelControllerServices() {
//                                    @Override
//                                    public Supplier servicesForBuild(BuildDefinition buildDefinition,
//                                                                     BuildState owner,
//                                                                     @Nullable BuildState parentBuild) {
//                                        return new Supplier() {
//                                            @Override
//                                            public void applyServicesTo(ServiceRegistration registration,
//                                                                        BuildScopeServices services) {
//
//                                            }
//                                        };
//                                    }
//                                };
//                            }
//
//                            DefaultListenerManager createListenerManager(DefaultListenerManager listenerManager) {
//                                return listenerManager.createChild(Scopes.BuildTree.class);
//                            }
//
//                            BuildCancellationToken createCancellationToken() {
//                                return new DefaultBuildCancellationToken();
//                            }
//
//                            BuildTreeState createBuildTreeState() {
//                                return buildTreeState;
//                            }
//
//                            CrossBuildSessionState createCrossBuildSessionState() {
//                                return crossBuildSessionState;
//                            }
//
//                            BuildActionRunner createBuildActionRunner() {
//                                return new ActionRunner();
//                            }
//
//                            IncludedBuildDependencySubstitutionsBuilder createIncludedBuildDependencySubstitutionsBuilder() {
//                                return new IncludedBuildDependencySubstitutionsBuilder();
//                            }
//
//                            BuildStateFactory createBuildStateFactory(
//                                    BuildTreeState buildTreeState,
//                                    ListenerManager listenerManager,
//                                    GradleUserHomeScopeServiceRegistry userHomeScopeServiceRegistry,
//                                    CrossBuildSessionState crossBuildSessionState,
//                                    BuildCancellationToken cancellationToken
//                            ) {
//                                return new BuildStateFactory(
//                                        buildTreeState,
//                                        listenerManager,
//                                        userHomeScopeServiceRegistry,
//                                        crossBuildSessionState,
//                                        cancellationToken
//                                );
//                            }
//
//                            IncludedBuildFactory createIncludedBuildFactory(
//                                    BuildTreeState buildTreeState
//                            ) {
//                                return new DefaultIncludedBuildFactory(buildTreeState,
//                                        DirectInstantiator.INSTANCE);
//                            }
//
//                            BuildStateRegistry createBuildStateRegistry(
//                                    IncludedBuildFactory includedBuildFactory,
//                                    IncludedBuildDependencySubstitutionsBuilder dependencySubstitutionsBuilder,
//                                    ListenerManager listenerManager,
//                                    BuildStateFactory buildStateFactory
//
//                            ) {
//                                return new DefaultIncludedBuildRegistry(
//                                        includedBuildFactory,
//                                        dependencySubstitutionsBuilder,
//                                        listenerManager,
//                                        buildStateFactory
//                                );
//                            }
//
//                            RootBuildLifecycleBuildActionExecutor createRootBuildActionExecutor(
//                                    BuildStateRegistry buildStateRegistry,
//                                    BuildActionRunner actionRunner
//                            ) {
//                                return new RootBuildLifecycleBuildActionExecutor(buildStateRegistry, actionRunner);
//                            }
//                        })
//                        .displayName("Global services")
//                        .build();
//            }
//
//            @Override
//            public BuildActionRunner.Result execute(BuildAction action) {
//                BuildActionRunner actionRunner = getServices().get(BuildActionRunner.class);
//                return actionRunner.run(action, getServices().get(BuildTreeLifecycleController.class));
//            }
//        });
    }

    private void build(BuildAction action, BuildSessionContext buildSession) {
        validator.validate(action.getStartParameter());

        BuildActionModelRequirements actionModelRequirements = new RunTasksRequirements(action.getStartParameter());
        BuildTreeModelControllerServices.Supplier modelServices = buildTreeModelControllerServices.servicesForBuildTree(actionModelRequirements);

        BuildActionRunner.Result result = null;
        try (BuildTreeState buildTree = new BuildTreeState(buildSession.getServices(), modelServices)) {
            result = buildTree.run((context -> context.execute(action)));
        } catch (Throwable t) {
            if (result == null) {
                // Did not create a result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException.throwAsUncheckedException(t);
            } else {
                // Cleanup has failed, combine the cleanup failure with other failures that may be packed in the result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException.throwAsUncheckedException(result.addFailure(t).getBuildFailure());
            }
        }
    }

    class ActionRunner implements BuildActionRunner {

        @Override
        public Result run(BuildAction action, BuildTreeLifecycleController buildController) {
            buildController.scheduleAndRunTasks();
            return Result.of(null);
        }
    }
}
