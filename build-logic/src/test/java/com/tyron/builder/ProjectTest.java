package com.tyron.builder;

import com.tyron.builder.model.Project;

import org.junit.Before;
import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.File;
import java.util.Map;

/**
 * Tests that the given {@link Project} has the correct files in it
 */
public class ProjectTest {

    private static final String PROJECT_NAME = "TestProject";

    private TestProject mTestProject;
    private Project mProject;

    @Before
    public void setup() {
        mTestProject = new TestProject(PROJECT_NAME);
        mProject = mTestProject.getProject();
    }

    @Test
    public void testProjectIsValid() {
        assertThat(mProject).isNotNull();
        assertThat(mProject.isValidProject());
    }

    @Test
    public void testJavaFiles() {
        Map<String, File> javaFiles = mProject.getJavaFiles();
        assertThat(javaFiles).isNotNull();
        assertThat(javaFiles).isNotEmpty();
        assertThat(javaFiles).containsEntry("com.tyron.test.MainActivity", new File(mProject.getJavaDirectory(), "com/tyron/test/MainActivity.java"));
    }
}
