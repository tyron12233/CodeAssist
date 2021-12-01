package com.tyron.builder.compiler.java;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.JavaBuilder;
import com.tyron.builder.compiler.TestUtil;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.dex.JavaD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.builder.project.impl.MockAndroidProject;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

public class JavaBuilderTest {

    private File mResourcesDirectory;
    private MockAndroidProject mJavaProject;

    @Before
    public void setup() throws Exception {
        mResourcesDirectory = TestUtil.getResourcesDirectory();
        mJavaProject = new MockAndroidProject(new File(mResourcesDirectory, "TestProject"));
        mJavaProject.setLambdaStubsJarFile(new File(mResourcesDirectory, "bootstraps/core-lambda-stubs.jar"));
        mJavaProject.setBootstrapFile(new File(mResourcesDirectory, "bootstraps/rt.jar"));
    }

    @Test
    public void testBuild() throws Exception {
        FileUtils.deleteDirectory(mJavaProject.getBuildDirectory());

        mJavaProject.addJavaFile(new File(mJavaProject.getJavaDirectory(),
                "com/tyron/test/Test.java"));

        JavaBuilder builder = new JavaBuilder(mJavaProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        List<Task<? super JavaProject>> tasksRan = builder.getTasksRan();
        assertThat(tasksRan).hasSize(builder.getTasks(BuildType.RELEASE).size());

        Task<? super JavaProject> firstTask = tasksRan.get(0);
        assertThat(firstTask).isInstanceOf(IncrementalJavaTask.class);
        assertThat(((IncrementalJavaTask) firstTask).getCompiledFiles()).hasSize(1);

        Task<? super JavaProject> secondTask = tasksRan.get(1);
        assertThat(secondTask).isInstanceOf(JavaD8Task.class);
        assertThat(((JavaD8Task) secondTask).getCompiledFiles()).hasSize(1);
    }
}
