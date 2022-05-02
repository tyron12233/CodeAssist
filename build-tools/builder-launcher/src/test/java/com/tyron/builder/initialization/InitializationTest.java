package com.tyron.builder.initialization;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.launcher.ProjectLauncher;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class InitializationTest {

    private ProjectLauncher projectLauncher;

    @Before
    public void setup() {
        File resourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(resourcesDir, "TestProject");

        StartParameterInternal startParameterInternal = new StartParameterInternal();
        startParameterInternal.setProjectDir(projectDir);
        startParameterInternal.setGradleHomeDir(new File(resourcesDir, ".gradle"));

        projectLauncher = new ProjectLauncher(startParameterInternal) {
            @Override
            public void configure(BuildProject project) {

            }
        };
    }

    @Test
    public void testInitialization() {
        projectLauncher.execute();
    }
}
