package com.tyron.builder.execution.taskgraph;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskExecutionAdapter;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.api.execution.TaskExecutionListener;
import com.tyron.builder.api.internal.BuildScopeListenerRegistrationListener;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.api.internal.tasks.NodeExecutionContext;
import com.tyron.builder.api.tasks.TaskState;
import com.tyron.builder.configuration.internal.ListenerBuildOperationDecorator;
import com.tyron.builder.execution.ProjectExecutionServiceRegistry;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.execution.plan.Node;
import com.tyron.builder.execution.plan.NodeExecutor;
import com.tyron.builder.execution.plan.PlanExecutor;
import com.tyron.builder.execution.plan.TaskNode;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.event.ListenerBroadcast;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationRef;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.listener.ClosureBackedMethodInvocationDispatch;
import com.tyron.builder.util.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@NonNullApi
public class DefaultTaskExecutionGraph implements TaskExecutionGraphInternal {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTaskExecutionGraph.class);

    private final PlanExecutor planExecutor;
    private final List<NodeExecutor> nodeExecutors;
    private final GradleInternal gradleInternal;
    private final ListenerBroadcast<TaskExecutionGraphListener> graphListeners;
    private final ListenerBroadcast<TaskExecutionListener> taskListeners;
    private final BuildScopeListenerRegistrationListener buildScopeListenerRegistrationListener;
    private final ProjectStateRegistry projectStateRegistry;
    private final ServiceRegistry globalServices;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ListenerBuildOperationDecorator listenerBuildOperationDecorator;
    private ExecutionPlan executionPlan;
    private List<Task> allTasks;
    private boolean hasFiredWhenReady;

    public DefaultTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors,
            BuildOperationExecutor buildOperationExecutor,
            ListenerBuildOperationDecorator listenerBuildOperationDecorator,
            GradleInternal gradleInternal,
            ListenerBroadcast<TaskExecutionGraphListener> graphListeners,
            ListenerBroadcast<TaskExecutionListener> taskListeners,
            BuildScopeListenerRegistrationListener buildScopeListenerRegistrationListener,
            ProjectStateRegistry projectStateRegistry,
            ServiceRegistry globalServices
    ) {
        this.planExecutor = planExecutor;
        this.nodeExecutors = nodeExecutors;
        this.buildOperationExecutor = buildOperationExecutor;
        this.listenerBuildOperationDecorator = listenerBuildOperationDecorator;
        this.gradleInternal = gradleInternal;
        this.graphListeners = graphListeners;
        this.taskListeners = taskListeners;
        this.buildScopeListenerRegistrationListener = buildScopeListenerRegistrationListener;
        this.projectStateRegistry = projectStateRegistry;
        this.globalServices = globalServices;
        this.executionPlan = ExecutionPlan.EMPTY;
    }

    @Override
    public void setContinueOnFailure(boolean continueOnFailure) {
        executionPlan.setContinueOnFailure(continueOnFailure);
    }

    @Override
    public void populate(ExecutionPlan plan) {
        executionPlan = plan;
        allTasks = null;
        if (!hasFiredWhenReady) {
            fireWhenReady();
            hasFiredWhenReady = true;
        } else if (!graphListeners.isEmpty()) {
            LOGGER.info("Ignoring listeners of task graph ready event, as this build ({}) has already executed work.", gradleInternal.getIdentityPath());
        }
    }

    @Override
    public void execute(ExecutionPlan plan, Collection<? super Throwable> failures) {
        assertIsThisGraphsPlan(plan);
        if (!hasFiredWhenReady) {
            throw new IllegalStateException("Task graph should be populated before execution starts.");
        }
        try (ProjectExecutionServiceRegistry projectExecutionServices = new ProjectExecutionServiceRegistry(globalServices)) {
            executeWithServices(projectExecutionServices, failures);
        } finally {
            executionPlan = ExecutionPlan.EMPTY;
        }
    }

    private void assertIsThisGraphsPlan(ExecutionPlan plan) {
        if (plan != executionPlan) {
            // Temporarily handle only a single plan
            throw new IllegalArgumentException();
        }
    }

    private void executeWithServices(ProjectExecutionServiceRegistry projectExecutionServices, Collection<? super Throwable> failures) {
        Timer clock = Time.startTimer();
        planExecutor.process(
                executionPlan,
                failures,
                new BuildOperationAwareExecutionAction(
                        buildOperationExecutor.getCurrentOperation(),
                        new InvokeNodeExecutorsAction(nodeExecutors, projectExecutionServices)
                )
        );
        LOGGER.debug("Timing: Executing the DAG took {}", clock.getElapsed());
    }

    @Override
    public void addTaskExecutionGraphListener(TaskExecutionGraphListener listener) {
        graphListeners.add(
                decorateListener("TaskExecutionGraph.addTaskExecutionGraphListener", listener)
        );
    }

    private TaskExecutionGraphListener decorateListener(String registrationPoint, TaskExecutionGraphListener listener) {
        return listenerBuildOperationDecorator.decorate(
                registrationPoint,
                TaskExecutionGraphListener.class,
                listener
        );
    }

    @Override
    public void removeTaskExecutionGraphListener(TaskExecutionGraphListener listener) {
        graphListeners.remove(listener);
    }

    @Override
    public void whenReady(final Closure closure) {
        graphListeners.add(
                new ClosureBackedMethodInvocationDispatch(
                        "graphPopulated",
                        listenerBuildOperationDecorator.decorate(
                                "TaskExecutionGraph.whenReady",
                                Cast.<Closure<?>>uncheckedCast(closure)
                        )
                )
        );
    }

    @Override
    public void whenReady(final Action<TaskExecutionGraph> action) {
        graphListeners.add(
                decorateListener("TaskExecutionGraph.whenReady", action::execute)
        );
    }

    @Override
    public void addTaskExecutionListener(TaskExecutionListener listener) {
        notifyListenerRegistration("TaskExecutionGraph.addTaskExecutionListener", listener);
        taskListeners.add(listener);
    }

    @Override
    public void removeTaskExecutionListener(TaskExecutionListener listener) {
        taskListeners.remove(listener);
    }

    @Override
    public void beforeTask(final Closure closure) {
        notifyListenerRegistration("TaskExecutionGraph.beforeTask", closure);
        taskListeners.add(new ClosureBackedMethodInvocationDispatch("beforeExecute", closure));
    }

    @Override
    public void beforeTask(final Action<Task> action) {
        notifyListenerRegistration("TaskExecutionGraph.beforeTask", action);
        taskListeners.add(new TaskExecutionAdapter() {
            @Override
            public void beforeExecute(Task task) {
                action.execute(task);
            }
        });
    }

    @Override
    public void afterTask(final Closure closure) {
        notifyListenerRegistration("TaskExecutionGraph.afterTask", closure);
        taskListeners.add(new ClosureBackedMethodInvocationDispatch("afterExecute", closure));
    }

    @Override
    public void afterTask(final Action<Task> action) {
        notifyListenerRegistration("TaskExecutionGraph.afterTask", action);
        taskListeners.add(new TaskExecutionAdapter() {
            @Override
            public void afterExecute(Task task, TaskState state) {
                action.execute(task);
            }
        });
    }

    private void notifyListenerRegistration(String registrationPoint, Object listener) {
        buildScopeListenerRegistrationListener.onBuildScopeListenerRegistration(
                listener,
                registrationPoint,
                this
        );
    }

    @Override
    public boolean hasTask(Task task) {
        return executionPlan.getTasks().contains(task);
    }

    @Override
    public boolean hasTask(String path) {
        for (Task task : executionPlan.getTasks()) {
            if (task.getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return executionPlan.size();
    }

    @Override
    public List<Task> getAllTasks() {
        if (allTasks == null) {
            allTasks = ImmutableList.copyOf(executionPlan.getTasks());
        }
        return allTasks;
    }

    @Override
    public List<Node> getScheduledWorkPlusDependencies() {
        return executionPlan.getScheduledNodesPlusDependencies();
    }

    @Override
    public Set<Task> getDependencies(Task task) {
        Node node = executionPlan.getNode(task);
        ImmutableSet.Builder<Task> builder = ImmutableSet.builder();
        for (Node dependencyNode : node.getDependencySuccessors()) {
            if (dependencyNode instanceof TaskNode) {
                builder.add(((TaskNode) dependencyNode).getTask());
            }
        }
        return builder.build();
    }

    /**
     * This action wraps the execution of a node into a build operation.
     */
    private static class BuildOperationAwareExecutionAction implements Action<Node> {
        private final BuildOperationRef parentOperation;
        private final Action<Node> delegate;

        BuildOperationAwareExecutionAction(BuildOperationRef parentOperation, Action<Node> delegate) {
            this.parentOperation = parentOperation;
            this.delegate = delegate;
        }

        @Override
        public void execute(Node node) {
            BuildOperationRef previous = CurrentBuildOperationRef.instance().get();
            CurrentBuildOperationRef.instance().set(parentOperation);
            try {
                delegate.execute(node);
            } finally {
                CurrentBuildOperationRef.instance().set(previous);
            }
        }
    }

    private static class InvokeNodeExecutorsAction implements Action<Node> {
        private final List<NodeExecutor> nodeExecutors;
        private final ProjectExecutionServiceRegistry projectExecutionServices;

        public InvokeNodeExecutorsAction(List<NodeExecutor> nodeExecutors, ProjectExecutionServiceRegistry projectExecutionServices) {
            this.nodeExecutors = nodeExecutors;
            this.projectExecutionServices = projectExecutionServices;
        }

        @Override
        public void execute(Node node) {
            NodeExecutionContext context = projectExecutionServices.forProject(node.getOwningProject());
            for (NodeExecutor nodeExecutor : nodeExecutors) {
                if (nodeExecutor.execute(node, context)) {
                    return;
                }
            }
            throw new IllegalStateException("Unknown type of node: " + node);
        }
    }

    @Override
    public Set<Task> getFilteredTasks() {
        /*
            Note: we currently extract this information from the execution plan because it's
            buried under functions in #filter. This could be detangled/simplified by introducing
            excludeTasks(Iterable<Task>) as an analog to addEntryTasks(Iterable<Task>).

            This is too drastic a change for the stage in the release cycle were exposing this information
            was necessary, therefore the minimal change solution was implemented.
         */
        return executionPlan.getFilteredTasks();
    }

    private void fireWhenReady() {
        // We know that we're running single-threaded here, so we can use coarse grained project locks
        projectStateRegistry.withMutableStateOfAllProjects(
                () -> buildOperationExecutor.run(
                        new NotifyTaskGraphWhenReady(DefaultTaskExecutionGraph.this, graphListeners.getSource(), gradleInternal)
                )
        );
    }

    private static class NotifyTaskGraphWhenReady implements RunnableBuildOperation {

        private final TaskExecutionGraph taskExecutionGraph;
        private final TaskExecutionGraphListener graphListener;
        private final GradleInternal gradleInternal;

        private NotifyTaskGraphWhenReady(TaskExecutionGraph taskExecutionGraph, TaskExecutionGraphListener graphListener, GradleInternal gradleInternal) {
            this.taskExecutionGraph = taskExecutionGraph;
            this.graphListener = graphListener;
            this.gradleInternal = gradleInternal;
        }

        @Override
        public void run(BuildOperationContext context) {
            graphListener.graphPopulated(taskExecutionGraph);
            context.setResult(NotifyTaskGraphWhenReadyBuildOperationType.RESULT);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor.displayName(
                    gradleInternal.contextualize("Notify task graph whenReady listeners"))
                    .details(
                            new NotifyTaskGraphWhenReadyDetails(
                                    gradleInternal.getIdentityPath()
                            )
                    );
        }
    }

    private static class NotifyTaskGraphWhenReadyDetails implements NotifyTaskGraphWhenReadyBuildOperationType.Details {

        private final Path buildPath;

        NotifyTaskGraphWhenReadyDetails(Path buildPath) {
            this.buildPath = buildPath;
        }

        @Override
        public String getBuildPath() {
            return buildPath.getPath();
        }

    }
}
