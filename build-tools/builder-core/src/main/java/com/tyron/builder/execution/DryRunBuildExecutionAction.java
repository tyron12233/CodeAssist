package com.tyron.builder.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.internal.build.ExecutionResult;

/**
 * A {@link BuildWorkExecutor} that disables all selected tasks before they are executed.
 */
public class DryRunBuildExecutionAction implements BuildWorkExecutor {
    private final StyledTextOutputFactory textOutputFactory;
    private final BuildWorkExecutor delegate;

    public DryRunBuildExecutionAction(StyledTextOutputFactory textOutputFactory, BuildWorkExecutor delegate) {
        this.textOutputFactory = textOutputFactory;
        this.delegate = delegate;
    }

    @Override
    public ExecutionResult<Void> execute(GradleInternal gradle, ExecutionPlan plan) {
        if (gradle.getStartParameter().isDryRun()) {
            for (Task task : plan.getTasks()) {
                textOutputFactory.create(DryRunBuildExecutionAction.class)
                        .append(((TaskInternal) task).getIdentityPath().getPath())
                        .append(" ")
                        .style(StyledTextOutput.Style.ProgressStatus)
                        .append("SKIPPED")
                        .println();
            }
            return ExecutionResult.succeeded();
        } else {
            return delegate.execute(gradle, plan);
        }
    }
}
