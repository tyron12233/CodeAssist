package org.gradle.api.internal.tasks.execution;

import org.gradle.api.GradleException;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskExecutionOutcome;
import org.gradle.api.internal.tasks.TaskStateInternal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.gradle.api.internal.tasks.TaskExecuter} which skips tasks whose
 * only if predicate evaluates to false
 */
public class SkipOnlyIfTaskExecuter implements TaskExecuter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkipOnlyIfTaskExecuter.class);

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
            state.setOutcome(new GradleException(String.format("Could not evaluate onlyIf predicate for %s.", task), t));
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
