package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;

import java.util.Collection;

public class DefaultProjectTaskLister implements ProjectTaskLister {
    @Override
    public Collection<Task> listProjectTasks(BuildProject project) {
        ProjectInternal projectInternal = (ProjectInternal) project;
        TaskContainerInternal tasks = projectInternal.getTasks();
        tasks.realize();
        return tasks;
    }
}
