package org.gradle.api.internal.project;

import org.gradle.api.BuildProject;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.TaskContainerInternal;

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
