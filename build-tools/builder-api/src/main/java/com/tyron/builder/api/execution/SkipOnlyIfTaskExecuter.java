package com.tyron.builder.api.execution;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionOutcome;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;

import java.util.logging.Logger;

/**
 * A {@link com.tyron.builder.api.internal.tasks.TaskExecuter} which skips tasks whose
 * only if predicate evaluates to false
 */
public class SkipOnlyIfTaskExecuter implements TaskExecuter {

    private static final Logger LOGGER = Logger.getLogger("SkipOnlyIfTaskExecuter");

    private final TaskExecuter executer;

    /**
     * @param executer The previous executer used for chaining multiple executers
     */
    public SkipOnlyIfTaskExecuter(TaskExecuter executer) {
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        boolean skip;
        try {
            skip = !task.getOnlyIf().test(task);
        } catch (Throwable t) {
            state.setOutcome(new BuildException(String.format("Could not evaluate onlyIf predicate for %s.", task), t));
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }

        if (skip) {
            LOGGER.info("Skipping " + task + " as task onlyIf is false.");
            state.setOutcome(TaskExecutionOutcome.SKIPPED);
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }

        // execute the previous executer
        return executer.execute(task, state, context);
    }
}
