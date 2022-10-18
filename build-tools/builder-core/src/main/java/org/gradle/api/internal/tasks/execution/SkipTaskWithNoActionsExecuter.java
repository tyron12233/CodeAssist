package org.gradle.api.internal.tasks.execution;

import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskExecutionOutcome;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.gradle.api.internal.tasks.TaskExecuter} which skips tasks that have no actions.
 */
public class SkipTaskWithNoActionsExecuter implements TaskExecuter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkipTaskWithNoActionsExecuter.class);
    private final TaskExecutionGraph taskExecutionGraph;
    private final TaskExecuter executer;

    public SkipTaskWithNoActionsExecuter(TaskExecutionGraph taskExecutionGraph, TaskExecuter executer) {
        this.taskExecutionGraph = taskExecutionGraph;
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task, TaskStateInternal state, TaskExecutionContext context) {
        if (!task.hasTaskActions()) {
            LOGGER.info("Skipping {} as it has no actions.", task);
            boolean upToDate = true;
            for (Task dependency : taskExecutionGraph.getDependencies(task)) {
                if (!dependency.getState().getSkipped()) {
                    upToDate = false;
                    break;
                }
            }
            state.setActionable(false);
            state.setOutcome(upToDate ? TaskExecutionOutcome.UP_TO_DATE : TaskExecutionOutcome.EXECUTED);
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }
        return executer.execute(task, state, context);
    }
}
