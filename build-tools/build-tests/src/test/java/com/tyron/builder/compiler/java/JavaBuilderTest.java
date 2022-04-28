package com.tyron.builder.compiler.java;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.JavaBuilder;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.dex.JavaD8Task;
import com.tyron.builder.compiler.incremental.java.IncrementalJavaTask;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.common.TestUtil;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class JavaBuilderTest {

    private File mResourcesDirectory;
    private MockFileManager mFileManager;
    private MockAndroidModule mJavaProject;

    @Before
    public void setup() throws Exception {
        mResourcesDirectory = TestUtil.getResourcesDirectory();

        File root = new File(mResourcesDirectory, "TestProject");
        mFileManager = new MockFileManager(root);
        mJavaProject = new MockAndroidModule(new File(root, "app"), mFileManager);
        mJavaProject.setLambdaStubsJarFile(new File(mResourcesDirectory,
                "bootstraps/core-lambda-stubs.jar"));
        mJavaProject.setBootstrapFile(new File(mResourcesDirectory, "bootstraps/rt.jar"));
    }

    @Test
    public void testBuild() throws Exception {
        try {
            FileUtils.deleteDirectory(mJavaProject.getBuildDirectory());
        } catch (IOException e) {
            if (!TestUtil.isWindows()) {
                throw e;
            }
        }

        mJavaProject.addJavaFile(new File(mJavaProject.getJavaDirectory(),
                "com/tyron/test/Test.java"));

        JavaBuilder builder = new JavaBuilder(null, mJavaProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        List<Task<? super JavaModule>> tasksRan = builder.getTasksRan();
        assertThat(tasksRan).hasSize(builder.getTasks(BuildType.RELEASE).size());

        Task<? super JavaModule> firstTask = tasksRan.get(0);
        assertThat(firstTask).isInstanceOf(IncrementalJavaTask.class);
        assertThat(((IncrementalJavaTask) firstTask).getCompiledFiles()).hasSize(1);

        Task<? super JavaModule> secondTask = tasksRan.get(1);
        assertThat(secondTask).isInstanceOf(JavaD8Task.class);
        assertThat(((JavaD8Task) secondTask).getCompiledFiles()).hasSize(1);
    }
}
