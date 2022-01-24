package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.MutableGraph;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.TaskDependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Stack;

@SuppressWarnings("UnstableApiUsage")
public class TaskExecutor {

    private MutableGraph<Task> mTaskGraph;

    public TaskExecutor() {
        mTaskGraph = GraphBuilder.directed()
                .allowsSelfLoops(false)
                .nodeOrder(ElementOrder.insertion())
                .build();
    }

    public void run(Task root) {
        mTaskGraph.addNode(root);
        buildDependencyGraph(root);
        execute(mTaskGraph, root);
    }

    public void run(TaskContainer taskContainer, Task root) {
        Set<? extends Task> dependencies = root.getTaskDependencies().getDependencies(root);
        for (Task dependency : dependencies) {
            run(taskContainer, dependency);
        }

        root.getActions().forEach(action -> action.execute(root));

        Set<? extends Task> mustRunAfterSet = root.getMustRunAfter().getDependencies(root);
        for (Task mustRunAfter : mustRunAfterSet) {
            run(taskContainer, mustRunAfter);
        }
    }

    private void execute(Graph<Task> graph, Task root) {
        ImmutableList<Task> reverse =
                ImmutableList.copyOf(Graphs.reachableNodes(graph, root))
                .reverse();
        reverse.forEach(task -> {
            task.getActions().forEach(action -> action.execute(task));
        });
    }

    private void buildDependencyGraph(Task root) {
        TaskDependency taskDependencies = root.getTaskDependencies();
        Set<? extends Task> dependencies = taskDependencies.getDependencies(root);
        for (Task dependency : dependencies) {
            mTaskGraph.putEdge(root, dependency);
            mTaskGraph.addNode(root);
            buildDependencyGraph(dependency);
        }
    }
}
