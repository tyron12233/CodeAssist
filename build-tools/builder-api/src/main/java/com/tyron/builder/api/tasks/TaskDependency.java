package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

import java.util.Set;

public interface TaskDependency {

    Set<? extends Task> getDependencies(Task task);
}
