package org.gradle.execution;

import org.gradle.api.Task;
import org.gradle.execution.BuildWorkExecutor;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.build.ExecutionResult;

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
