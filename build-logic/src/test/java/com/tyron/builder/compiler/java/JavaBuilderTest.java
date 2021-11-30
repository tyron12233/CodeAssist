package com.tyron.builder.compiler.java;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.truth.Truth;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.JavaBuilder;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.dex.JavaD8Task;
import com.tyron.builder.compiler.incremental.dex.IncrementalD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.builder.project.impl.MockAndroidProject;
import com.tyron.builder.project.impl.MockJavaProject;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class JavaBuilderTest {

    private File mResourcesDirectory;
    private MockAndroidProject mJavaProject;

    @Before
    public void setup() throws Exception {
        mResourcesDirectory = getResourcesDirectory();
        mJavaProject = new MockAndroidProject(new File(mResourcesDirectory, "TestProject"));
        mJavaProject.setLambdaStubsJarFile(new File(mResourcesDirectory, "bootstraps/core-lambda-stubs.jar"));
        mJavaProject.setBootstrapFile(new File(mResourcesDirectory, "bootstraps/rt.jar"));
    }

    public static File getResourcesDirectory() throws IOException {
        File currentDirFile = Paths.get(".").toFile();
        String helper = currentDirFile.getAbsolutePath();
        String currentDir = helper.substring(0,
                helper.length() - 1);
        return new File(new File(currentDir), "src/test/resources");
    }

    @Test
    public void testBuild() throws Exception {
        FileUtils.deleteDirectory(mJavaProject.getBuildDirectory());

        mJavaProject.addJavaFile(new File(mJavaProject.getJavaDirectory(),
                "com/tyron/test/Test.java"));

        JavaBuilder builder = new JavaBuilder(mJavaProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        List<Task<JavaProject>> tasksRan = builder.getTasksRan();
        assertThat(tasksRan).hasSize(builder.getTasks().size());

        Task<JavaProject> firstTask = tasksRan.get(0);
        assertThat(firstTask).isInstanceOf(IncrementalJavaTask.class);
        assertThat(((IncrementalJavaTask) firstTask).getCompiledFiles()).hasSize(1);

        Task<JavaProject> secondTask = tasksRan.get(1);
        assertThat(secondTask).isInstanceOf(JavaD8Task.class);
        assertThat(((JavaD8Task) secondTask).getCompiledFiles()).hasSize(1);
    }
}
