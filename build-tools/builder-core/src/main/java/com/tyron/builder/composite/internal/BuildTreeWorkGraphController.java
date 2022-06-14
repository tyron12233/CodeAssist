package com.tyron.builder.composite.internal;


import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.buildtree.BuildTreeWorkGraph;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.util.function.Function;

/**
 * A service that allows work graphs to be created, populated and executed.
 */
@ServiceScope(Scopes.BuildTree.class)
public interface BuildTreeWorkGraphController {
    /**
     * Locates a task node in another build's work graph. Does not schedule the task for execution, use {@link IncludedBuildTaskResource#queueForExecution()} to queue the task for execution.
     */
    IncludedBuildTaskResource locateTask(BuildIdentifier targetBuild, TaskInternal task);

    /**
     * Locates a task node in another build's work graph. Does not schedule the task for execution, use {@link IncludedBuildTaskResource#queueForExecution()} to queue the task for execution.
     */
    IncludedBuildTaskResource locateTask(BuildIdentifier targetBuild, String taskPath);

    /**
     * Runs the given action against a new, empty work graph. This allows tasks to be run while calculating the task graph of the build tree, for example to run `buildSrc` tasks or
     * to build local plugins in an included build.
     */
    <T> T withNewWorkGraph(Function<? super BuildTreeWorkGraph, T> action);
}
