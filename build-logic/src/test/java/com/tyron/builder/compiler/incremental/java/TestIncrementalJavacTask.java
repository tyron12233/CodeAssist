package com.tyron.builder.compiler.incremental.java;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.tyron.builder.BuildModule;
import com.tyron.builder.TestProject;
import com.tyron.builder.compiler.StdLogger;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.java.JavaTask;
import com.tyron.builder.model.Project;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = "src/test/AndroidManifest.xml")
public class TestIncrementalJavacTask {

    private static final String PROJECT_NAME = "TestProject";

    private static final String TEST_CLASS = "package com.tyron.test;\n\n" +
            "public class Test {\n" +
            "   public static void main(String[] args) {\n" +
            "\n" +
            "   }\n" +
            "}";

    private Project mProject;
    private TestProject mTestProject;

    @Before
    public void setup() {
        BuildModule.initialize(ApplicationProvider.getApplicationContext());
        mTestProject = new TestProject(PROJECT_NAME);
        mProject = mTestProject.getProject();
    }

    @Test
    public void testCompile() throws Exception {

        IncrementalJavaTask task = new IncrementalJavaTask();
        task.prepare(mProject, StdLogger.INSTANCE);
        task.run();

        testIncrementalCompilation();

    }

    private void testIncrementalCompilation() throws Exception {
        IncrementalJavaTask task = new IncrementalJavaTask();

        File testClass = mTestProject.javaFile("com.tyron.test", "Test", TEST_CLASS);
        task.prepare(mProject, StdLogger.INSTANCE);
        task.run();

        System.out.println(task.getCompiledFiles());

        assertThat(task.getCompiledFiles()).containsExactly(testClass);
    }
}
