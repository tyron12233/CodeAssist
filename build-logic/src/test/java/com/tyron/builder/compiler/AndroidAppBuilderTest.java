package com.tyron.builder.compiler;

import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.impl.MockAndroidProject;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class AndroidAppBuilderTest {

    private MockAndroidProject mProject;

    @Before
    public void setup() throws Exception {
        File resourcesDir = ProjectUtil.getResourcesDirectory();
        File projectDir = new File(resourcesDir, "TestProject");
        mProject = new MockAndroidProject(projectDir);
        mProject.setLambdaStubsJarFile(new File(resourcesDir, "bootstraps/core-lambda-stubs.jar"));
        mProject.setBootstrapFile(new File(resourcesDir, "bootstraps/rt.jar"));
    }

    @Test
    public void testBuild() throws Exception {
        mProject.addJavaFile(new File(mProject.getJavaDirectory(),
                "com/tyron/test/MainActivity.java"));

        mProject.open();

        AndroidAppBuilder builder = new AndroidAppBuilder(mProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);
    }
}
