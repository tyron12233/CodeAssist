package com.tyron.builder.api.internal.execution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.api.execution.plan.ExecutionPlan;
import com.tyron.builder.api.execution.plan.Node;
import com.tyron.builder.api.execution.plan.NodeExecutor;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.execution.plan.TaskNode;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class DefaultTaskExecutionGraph implements TaskExecutionGraphInternal {

    private final PlanExecutor planExecutor;
    private final List<NodeExecutor> nodeExecutors;
//    private final GradleInternal gradleInternal;
//    private final ListenerBroadcast<TaskExecutionGraphListener> graphListeners;
//    private final ListenerBroadcast<org.gradle.api.execution.TaskExecutionListener> taskListeners;
//    private final BuildScopeListenerRegistrationListener buildScopeListenerRegistrationListener;
//    private final ServiceRegistry globalServices;
//    private final BuildOperationExecutor buildOperationExecutor;
//    private final ListenerBuildOperationDecorator listenerBuildOperationDecorator;
    private ExecutionPlan executionPlan;
    private List<Task> allTasks;
    private boolean hasFiredWhenReady;

    public DefaultTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors
//            BuildOperationExecutor buildOperationExecutor,
//            ListenerBuildOperationDecorator listenerBuildOperationDecorator,
//            GradleInternal gradleInternal,
//            ListenerBroadcast<TaskExecutionGraphListener> graphListeners,
//            ListenerBroadcast<TaskExecutionListener> taskListeners,
//            BuildScopeListenerRegistrationListener buildScopeListenerRegistrationListener,
//            ServiceRegistry globalServices
    ) {
        this.planExecutor = planExecutor;
        this.nodeExecutors = nodeExecutors;
//        this.buildOperationExecutor = buildOperationExecutor;
//        this.listenerBuildOperationDecorator = listenerBuildOperationDecorator;
//        this.gradleInternal = gradleInternal;
//        this.graphListeners = graphListeners;
//        this.taskListeners = taskListeners;
//        this.buildScopeListenerRegistrationListener = buildScopeListenerRegistrationListener;
//        this.globalServices = globalServices;
        this.executionPlan = ExecutionPlan.EMPTY;
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

    @Override
    public void populate(ExecutionPlan plan) {
        try {
            executionPlan.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        executionPlan = plan;
        allTasks = null;
        if (!hasFiredWhenReady) {
//            fireWhenReady();
            hasFiredWhenReady = true;
        }
//        else if (!graphListeners.isEmpty()) {
//            LOGGER.info("Ignoring listeners of task graph ready event, as this build ({}) has already executed work.", gradleInternal.getIdentityPath());
//        }
    }

    @Override
    public void execute(ExecutionPlan plan, Collection<? super Throwable> taskFailures) {
        assertIsThisGraphsPlan(plan);
        if (!hasFiredWhenReady) {
            throw new IllegalStateException("Task graph should be populated before execution starts.");
        }
    }

    private void assertIsThisGraphsPlan(ExecutionPlan plan) {
        if (plan != executionPlan) {
            // Temporarily handle only a single plan
            throw new IllegalArgumentException();
        }
    }


    @Override
    public void setContinueOnFailure(boolean continueOnFailure) {

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
}
