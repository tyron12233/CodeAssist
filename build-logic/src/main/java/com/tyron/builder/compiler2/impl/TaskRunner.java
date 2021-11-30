package com.tyron.builder.compiler2.impl;

import com.tyron.builder.compiler2.api.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskRunner {

    private final List<Task> mTasks;
    private final Map<String, Task> mTasksRan = new HashMap<>();

    public TaskRunner(List<Task> tasks) {
        mTasks = new ArrayList<>(tasks);
    }

    public void execute() {
        mTasks.forEach(task -> {
            task.getActions().forEach(action -> {
                action.execute(task);
            });
        });
    }
}
