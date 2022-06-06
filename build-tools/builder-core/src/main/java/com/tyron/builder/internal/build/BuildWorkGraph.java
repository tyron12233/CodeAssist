package com.tyron.builder.internal.build;

import java.util.Collection;
import java.util.function.Consumer;

public interface BuildWorkGraph {
    /**
     * Schedules the given tasks and all of their dependencies in this work graph.
     */
    boolean schedule(Collection<ExportedTaskNode> taskNodes);

    /**
     * Adds tasks and other nodes to this work graph.
     */
    void populateWorkGraph(Consumer<? super BuildLifecycleController.WorkGraphBuilder> action);

    /**
     * Finalize the work graph for execution, after all work has been scheduled. This method should not schedule any additional work.
     */
    void finalizeGraph();

    /**
     * Runs all work in this graph.
     */
    ExecutionResult<Void> runWork();
}
