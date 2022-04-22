package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

import java.util.List;

public class TestTaskExecution extends BaseProjectTestCase {

    @Override
    public void configure(BuildProject project) {
        TaskContainer tasks = project.getTasks();
        tasks.register("MyTask", task -> {
            task.doLast(__ -> {
                System.out.println("Running " + task.getName());
            });
        });
    }

    @Override
    public List<String> getTasks() {
        return ImmutableList.of("MyTask");
    }

}
