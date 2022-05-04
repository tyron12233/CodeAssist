package com.tyron.builder.internal.buildtree;

/**
 * Responsible for preparing the work graph for the build tree.
 */
public interface BuildTreeWorkPreparer {
    /**
     * Prepares the given work graph for execution. May configure the build model and calculate the task graph from this, or may load a cached task graph if available.
     */
    void scheduleRequestedTasks(BuildTreeWorkGraph graph);
}