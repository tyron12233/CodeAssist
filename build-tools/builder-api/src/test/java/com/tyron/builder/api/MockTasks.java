package com.tyron.builder.api;

import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.tasks.TaskContainer;

public class MockTasks extends TestTaskExecutionCase {

    public void test() {
        Action<BuildProject> evaluationAction = project -> {

            TaskContainer tasks = project.getTasks();
            tasks.register("AAPT2");
        };
    }
}
