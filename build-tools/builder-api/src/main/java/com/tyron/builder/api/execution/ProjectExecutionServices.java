package com.tyron.builder.api.execution;

import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.tasks.TaskExecuter;

public class ProjectExecutionServices extends DefaultServiceRegistry {

    public ProjectExecutionServices(ProjectInternal project) {
        super("Configured project services for '" + project.getPath() + "'", project.getServices());
    }

    public TaskExecuter createTaskExecuter() {
        TaskExecuter executer = new ExecuteActionsTaskExecuter();
        executer = new SkipOnlyIfTaskExecuter(executer);
        return executer;
    }

    ExecutionNodeAccessHierarchies.InputNodeAccessHierarchy createInputNodeAccessHierarchies(ExecutionNodeAccessHierarchies hierarchies) {
        return hierarchies.createInputHierarchy();
    }
}
