package com.tyron.builder.execution;

import com.google.common.collect.Sets;
import com.tyron.builder.api.Task;
import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.execution.taskgraph.TaskExecutionGraphInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.internal.build.ExecutionResult;

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
        Set<BuildProject> seen = Sets.newHashSet();
        for (Task task : graph.getAllTasks()) {
            if (seen.add(task.getProject())) {
                ProjectInternal projectInternal = (ProjectInternal) task.getProject();
                projectInternal.bindAllModelRules();
            }
        }
    }
}
