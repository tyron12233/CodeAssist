package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;

import java.util.LinkedHashSet;
import java.util.Set;

public class TaskSelection {
    private final String projectPath;
    private final String taskName;
    private final TaskSelectionResult taskSelectionResult;

    public TaskSelection(String projectPath, String taskName, TaskSelectionResult tasks) {
        this.projectPath = projectPath;
        this.taskName = taskName;
        this.taskSelectionResult = tasks;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getTaskName() {
        return taskName;
    }

    public Set<Task> getTasks() {
        LinkedHashSet<Task> result = new LinkedHashSet<Task>();
        taskSelectionResult.collectTasks(result);
        return result;
    }
}
