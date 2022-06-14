package com.tyron.builder.initialization;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.launcher.ProjectLauncher;
import com.tyron.builder.plugin.CodeAssistPlugin;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class InitializationTest {

    private ProjectLauncher projectLauncher;

    @Before
    public void setup() {
        System.setProperty("org.gradle.native", "true");

        CodeAssistPlugin plugin = null;
        File resourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(resourcesDir, "TestProject");

        StartParameterInternal startParameterInternal = new StartParameterInternal();
        startParameterInternal.setLogLevel(LogLevel.DEBUG);
        startParameterInternal.setShowStacktrace(ShowStacktrace.ALWAYS_FULL);
        startParameterInternal.setProjectDir(projectDir);
        startParameterInternal.setBuildCacheEnabled(false);
        startParameterInternal.setGradleUserHomeDir(new File(resourcesDir, ".gradle"));
        startParameterInternal.setTaskNames(ImmutableList.of(":consumer:compileJava"));

        projectLauncher = new ProjectLauncher(startParameterInternal);

        LoggingManagerInternal loggingManagerInternal =
                projectLauncher.getGlobalServices().get(LoggingManagerInternal.class);
        loggingManagerInternal.attachSystemOutAndErr();
    }

    @Test
    public void testInitialization() {
        projectLauncher.execute();
    }
}
