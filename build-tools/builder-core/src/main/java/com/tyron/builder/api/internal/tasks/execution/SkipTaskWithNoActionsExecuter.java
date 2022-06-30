package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionOutcome;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link com.tyron.builder.api.internal.tasks.TaskExecuter} which skips tasks that have no actions.
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
