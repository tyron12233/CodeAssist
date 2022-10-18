package org.gradle.api.internal.project;

import org.gradle.api.Project;
import org.gradle.api.Task;

import java.util.Collection;

/**
 * Service to provide all tasks in a project including both regular tasks,
 * and implicit tasks.
 */
public interface ProjectTaskLister {
    Collection<Task> listProjectTasks(Project project);
}