package org.gradle.initialization;

import com.google.common.collect.ImmutableList;
import org.gradle.api.BuildProject;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.launcher.ProjectLauncher;
import org.gradle.plugin.CodeAssistPlugin;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class InitializationTest {

    private ProjectLauncher projectLauncher;

    @Before
    public void setup() {
        System.setProperty("org.gradle.native", "true");

        File resourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(resourcesDir, "TestProject");

        StartParameterInternal startParameterInternal = new StartParameterInternal();
        startParameterInternal.setLogLevel(LogLevel.LIFECYCLE);
        startParameterInternal.setShowStacktrace(ShowStacktrace.ALWAYS_FULL);
        startParameterInternal.setProjectDir(projectDir);
        startParameterInternal.setBuildCacheEnabled(true);
        startParameterInternal.setGradleUserHomeDir(new File(resourcesDir, ".gradle"));
        startParameterInternal.setTaskNames(ImmutableList.of(":consumer:assemble"));

        projectLauncher = new ProjectLauncher(startParameterInternal);

        LoggingManagerInternal loggingManagerInternal =
                projectLauncher.getGlobalServices().get(LoggingManagerInternal.class);
        loggingManagerInternal.attachSystemOutAndErr();
        loggingManagerInternal.attachProcessConsole(ConsoleOutput.Plain);
    }

    @Test
    public void testInitialization() {
        projectLauncher.execute();
    }
}
