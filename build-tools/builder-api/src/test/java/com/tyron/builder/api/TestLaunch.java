package com.tyron.builder.api;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.launcher.ProjectLauncher;
import com.tyron.common.TestUtil;

import org.junit.Test;

import java.io.File;

public class TestLaunch {

    @Test
    public void testProjectBuilder() {
        File resourcesDirectory = TestUtil.getResourcesDirectory();
        File gradleUserHomeDir = new File(resourcesDirectory, ".gradle");
        File testProjectDir = new File(resourcesDirectory, "TestProject");

        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setGradleUserHomeDir(gradleUserHomeDir);
        startParameter.setProjectDir(testProjectDir);
        startParameter.setTaskNames(ImmutableList.of("testTask"));

        ProjectLauncher projectLauncher = new ProjectLauncher(startParameter) {
            @Override
            public void configure(BuildProject project) {
                project.getTasks().register("testTask", new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        task.getLogger().info("Configuring");

                        task.doLast(new Action<Task>() {
                            @Override
                            public void execute(Task task) {
                                task.getLogger().info("Executing");
                            }
                        });
                    }
                });
            }
        };
        projectLauncher.execute();
    }
}
