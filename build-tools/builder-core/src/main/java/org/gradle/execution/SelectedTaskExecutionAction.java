package org.gradle.execution;

import com.google.common.collect.Sets;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.Project;
import org.gradle.internal.build.ExecutionResult;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class SelectedTaskExecutionAction implements BuildWorkExecutor {
    @Override
    public ExecutionResult<Void> execute(GradleInternal gradle, ExecutionPlan plan) {
        TaskExecutionGraphInternal taskGraph = gradle.getTaskGraph();
        if (gradle.getStartParameter().isContinueOnFailure()) {
            taskGraph.setContinueOnFailure(true);
        }

        bindAllReferencesOfProject(taskGraph);
        List<Throwable> taskFailures = new LinkedList<>();
        taskGraph.execute(plan, taskFailures);
        return ExecutionResult.maybeFailed(taskFailures);
    }

    private void bindAllReferencesOfProject(TaskExecutionGraph graph) {
        Set<Project> seen = Sets.newHashSet();
        for (Task task : graph.getAllTasks()) {
            if (seen.add(task.getProject())) {
                ProjectInternal projectInternal = (ProjectInternal) task.getProject();
                projectInternal.bindAllModelRules();
            }
        }
    }
}
