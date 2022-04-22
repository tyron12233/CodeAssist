package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

import java.io.File;
import java.util.List;

public class TestTaskInputOutputs extends BaseProjectTestCase {
    // Lambdas are not supported for gradle caching since java uses invokedynamic on them,
    // making it impossible for gradle to track the class and its tasks
    // see https://docs.gradle.org/current/userguide/validation_problems.html#implementation_unknown
    @SuppressWarnings("Convert2Lambda")
    @Override
    public void configure(BuildProject project) {
        File outputDir = new File(project.getBuildDir(), "output");
        File input = new File(project.getBuildDir().getParent(), "Test.java");

        TaskContainer tasks = project.getTasks();
        tasks.register("Task", new Action<Task>() {
            @Override
            public void execute(Task task) {
                task.getOutputs().dir(outputDir);
                task.getInputs().file(input);

                task.doLast(new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        System.out.println("RUNNING");
                    }
                });
            }
        });
    }

    @Override
    public List<String> getTasks() {
        return ImmutableList.of("Task");
    }
}
