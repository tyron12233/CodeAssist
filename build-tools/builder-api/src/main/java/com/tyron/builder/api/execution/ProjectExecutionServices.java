package com.tyron.builder.api.execution;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.service.ServiceRegistration;
import com.tyron.builder.api.internal.tasks.TaskExecuter;

public class ProjectExecutionServices extends DefaultServiceRegistry {

    public ProjectExecutionServices(ProjectInternal project) {
        super("Configured project services for '" + project.getPath() + "'", project.getServices());

        register(serviceRegistration -> serviceRegistration.add(TaskExecuter.class, createTaskExecuter()));
    }

    public TaskExecuter createTaskExecuter() {
        return new ExecuteActionsTaskExecuter();
    }
}
