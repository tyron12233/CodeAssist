package com.tyron.builder.composite.internal;


import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.ExecutionResult;

import java.io.Closeable;

interface BuildControllers extends Closeable {
    /**
     * Finish populating work graphs, once all entry point tasks have been scheduled.
     */
    void populateWorkGraphs();

    /**
     * Starts running any scheduled tasks. Does nothing when {@link #populateWorkGraphs()} has not been called to schedule the tasks.
     */
    void startExecution();

    /**
     * Blocks until all scheduled tasks have completed.
     */
    ExecutionResult<Void> awaitCompletion();

    /**
     * Locates the controller for a given build, adding it if not present.
     */
    BuildController getBuildController(BuildState build);

    @Override
    void close();
}
