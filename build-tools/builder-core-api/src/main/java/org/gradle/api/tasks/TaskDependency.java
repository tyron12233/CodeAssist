package org.gradle.api.tasks;

import org.gradle.api.Task;

import java.util.Set;

public interface TaskDependency {

    Set<? extends Task> getDependencies(Task task);
}
