package com.tyron.builder.api.tasks;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.tasks.WorkDependencyResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressWarnings("UnstableApiUsage")
public class TaskContainer {

    private final MutableGraph<Task> mTaskGraph;
    private List<Task> mTasks;

    public TaskContainer() {
        mTaskGraph = GraphBuilder.undirected()
                .allowsSelfLoops(false)
                .nodeOrder(ElementOrder.stable())
                .build();
        mTasks = new ArrayList<>();
    }

    public  void registerTask(Task task) {
        mTasks.add(task);
    }

    public Graph<Task> getTaskGraph() {
        return mTaskGraph;
    }

    public List<Task> getTasks() {
        return mTasks;
    }
}
