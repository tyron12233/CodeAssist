package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.internal.plugins.PluginRegistry;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;
import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.execution.ProjectConfigurer;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.api.execution.TaskExecutionListener;
import com.tyron.builder.execution.TaskSelector;
import com.tyron.builder.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.execution.plan.LocalTaskNodeExecutor;
import com.tyron.builder.execution.plan.NodeExecutor;
import com.tyron.builder.execution.plan.PlanExecutor;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.event.ListenerBroadcast;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.execution.taskgraph.DefaultTaskExecutionGraph;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.internal.id.UniqueId;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.options.OptionReader;
import com.tyron.builder.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.internal.work.AsyncWorkTracker;
import com.tyron.builder.internal.work.DefaultAsyncWorkTracker;
import com.tyron.builder.configuration.project.BuiltInCommand;
import com.tyron.builder.execution.BuildConfigurationAction;
import com.tyron.builder.execution.BuildConfigurationActionExecuter;
import com.tyron.builder.execution.BuildOperationFiringBuildWorkerExecutor;
import com.tyron.builder.execution.DefaultBuildConfigurationActionExecuter;
import com.tyron.builder.execution.DefaultTasksBuildExecutionAction;
import com.tyron.builder.execution.DryRunBuildExecutionAction;
import com.tyron.builder.execution.SelectedTaskExecutionAction;
import com.tyron.builder.execution.TaskNameResolvingBuildConfigurationAction;
import com.tyron.builder.execution.commandline.CommandLineTaskConfigurer;
import com.tyron.builder.execution.commandline.CommandLineTaskParser;
import com.tyron.builder.initialization.DefaultTaskExecutionPreparer;
import com.tyron.builder.initialization.TaskExecutionPreparer;
import com.tyron.builder.internal.buildtree.BuildModelParameters;
import com.tyron.builder.internal.cleanup.DefaultBuildOutputCleanupRegistry;
import com.tyron.builder.execution.taskgraph.TaskListenerInternal;
import com.tyron.builder.internal.logging.LoggingManagerInternal;

import java.util.LinkedList;
import java.util.List;

public class GradleScopeServices extends DefaultServiceRegistry {
    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent) {
        super(parent);
        register(registration -> {
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerGradleServices(registration);
            }
        });
    }

    BuildOutputCleanupRegistry createBuildOutputCleanupRegistry(
            FileCollectionFactory factory
    ) {
        return new DefaultBuildOutputCleanupRegistry(factory);
    }

    AsyncWorkTracker createAsyncWorkTracker(
            WorkerLeaseService workerLeaseService
    ) {
        return new DefaultAsyncWorkTracker(workerLeaseService);
    }

    LocalTaskNodeExecutor createLocalTaskNodeExecutor(ExecutionNodeAccessHierarchies executionNodeAccessHierarchies) {
        return new LocalTaskNodeExecutor(
                executionNodeAccessHierarchies.getOutputHierarchy()
        );
    }

//    WorkNodeExecutor createWorkNodeExecutor() {
//        return new WorkNodeExecutor();
//    }

    ListenerBroadcast<TaskExecutionGraphListener> createTaskExecutionGraphListenerBroadcast(
            ListenerManager listenerManager
    ) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionGraphListener.class);
    }

    ListenerBroadcast<TaskExecutionListener> createTaskExecutionListenerBroadcast(
            ListenerManager listenerManager
    ) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionListener.class);
    }

    TaskExecutionGraphInternal createTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors,
            BuildOperationExecutor buildOperationExecutor,
            GradleInternal gradle,
            ListenerBroadcast<TaskExecutionGraphListener> taskExecutionGraphListenerListeners,
            ListenerBroadcast<TaskExecutionListener> taskExecutionListeners,
            ServiceRegistry gradleScopedServices
    ) {
        return new DefaultTaskExecutionGraph(
                planExecutor,
                nodeExecutors,
                buildOperationExecutor,
                gradle,
                taskExecutionGraphListenerListeners,
                taskExecutionListeners,
                gradleScopedServices
        );
    }

    PluginRegistry createPluginRegistry(PluginRegistry parentRegistry) {
        return parentRegistry.createChild(get(GradleInternal.class).getClassLoaderScope());
    }

//    PluginManagerInternal createPluginManager(Instantiator instantiator, GradleInternal gradleInternal, PluginRegistry pluginRegistry, InstantiatorFactory instantiatorFactory, BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext, CollectionCallbackActionDecorator decorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
//        PluginTarget target = new ImperativeOnlyPluginTarget<>(gradleInternal);
//        return instantiator.newInstance(DefaultPluginManager.class, pluginRegistry, instantiatorFactory.inject(this), target, buildOperationExecutor, userCodeApplicationContext, decorator, domainObjectCollectionFactory);
//    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            @Override
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject, loggingManagerInternalFactory);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    BuildConfigurationActionExecuter createBuildConfigurationActionExecuter(CommandLineTaskParser commandLineTaskParser, ProjectConfigurer projectConfigurer, List<BuiltInCommand> builtInCommands) {
        List<BuildConfigurationAction> taskSelectionActions = new LinkedList<>();
        taskSelectionActions.add(new DefaultTasksBuildExecutionAction(projectConfigurer, builtInCommands));
        taskSelectionActions.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));
        return new DefaultBuildConfigurationActionExecuter(taskSelectionActions);
    }

    TaskExecutionPreparer createTaskExecutionPreparer(BuildConfigurationActionExecuter buildConfigurationActionExecuter, BuildOperationExecutor buildOperationExecutor, BuildModelParameters buildModelParameters) {
        return new DefaultTaskExecutionPreparer(buildConfigurationActionExecuter, buildOperationExecutor, buildModelParameters);
    }

    OptionReader createOptionReader() {
        return new OptionReader();
    }

    CommandLineTaskParser createCommandLineTaskParser(OptionReader optionReader, TaskSelector taskSelector) {
        return new CommandLineTaskParser(new CommandLineTaskConfigurer(optionReader), taskSelector);
    }

    BuildWorkExecutor createBuildExecuter(StyledTextOutputFactory textOutputFactory, BuildOperationExecutor buildOperationExecutor) {
        return new BuildOperationFiringBuildWorkerExecutor(
                new DryRunBuildExecutionAction(textOutputFactory,
                        new SelectedTaskExecutionAction()),
                buildOperationExecutor);
    }

    TaskExecutionListener createTaskExecutionListener(ListenerBroadcast<TaskExecutionListener> broadcast) {
        return broadcast.getSource();
    }

    TaskListenerInternal createTaskListenerInternal(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(TaskListenerInternal.class);
    }

    protected ConfigurationTargetIdentifier createConfigurationTargetIdentifier(GradleInternal gradle) {
        return ConfigurationTargetIdentifier.of(gradle);
    }

    // This needs to go here instead of being “build tree” scoped due to the GradleBuild task.
    // Builds launched by that task are part of the same build tree, but should have their own invocation ID.
    // Such builds also have their own root Gradle object.
    protected BuildInvocationScopeId createBuildInvocationScopeId(GradleInternal gradle) {
        GradleInternal rootGradle = gradle.getRoot();
        if (gradle == rootGradle) {
            return new BuildInvocationScopeId(UniqueId.generate());
        } else {
            return rootGradle.getServices().get(BuildInvocationScopeId.class);
        }
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}
