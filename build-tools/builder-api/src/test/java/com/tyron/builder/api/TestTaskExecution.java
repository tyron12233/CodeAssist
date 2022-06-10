package org.gradle.api;

import com.google.common.collect.ImmutableList;
import org.gradle.api.BuildProject;
import org.gradle.api.tasks.TaskContainer;

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
