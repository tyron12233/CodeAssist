package com.tyron.builder.api;

import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

import org.junit.Test;

import java.io.File;

public class TestTaskInputOutputs extends TestTaskExecutionCase {
    // Lambdas are not supported for gradle caching since java uses invokedynamic on them,
    // making it impossible for gradle to track the class and its tasks
    // see https://docs.gradle.org/current/userguide/validation_problems.html#implementation_unknown
    @SuppressWarnings("Convert2Lambda")
    @Test
    public void testTaskOutputs() {
        Action<BuildProject> evaluationAction = project -> {

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
        };

        evaluateProject(project, evaluationAction);
        executeProject(project, "Task");
    }
}
