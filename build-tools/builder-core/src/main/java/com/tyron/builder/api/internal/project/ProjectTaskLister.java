package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Task;

import java.util.Collection;

/**
 * Service to provide all tasks in a project including both regular tasks,
 * and implicit tasks.
 */
public interface ProjectTaskLister {
    Collection<Task> listProjectTasks(BuildProject project);
}