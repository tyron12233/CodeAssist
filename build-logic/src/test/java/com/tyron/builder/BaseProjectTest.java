package com.tyron.builder;

import com.tyron.builder.model.Project;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;


public class BaseProjectTest {

    public static final String MODULE_NAME = "build-logic";
    public static final String PROJECT_NAME = "TestProject";

    public Project mProject;

    @Before
    public void setup() {
        mProject = new Project(new File(resolveBasePath(), PROJECT_NAME));
    }

    @Test
    public void testProjectInitialization() {
        assertThat(mProject).isNotNull();
        assertThat(mProject.isValidProject());
    }

    @Test
    public void testProjectFiles() {
        Map<String, File> javaFiles = mProject.getJavaFiles();
        assertThat(javaFiles).isNotNull();
        assertThat(javaFiles).isNotEmpty();
    }

    public static String resolveBasePath() {
        final String path = "." + MODULE_NAME + "/src/test/resources";
        if (Arrays.asList(Objects.requireNonNull(new File("./").list())).contains(MODULE_NAME)) {
            return path;
        }
        return "../" + path;
    }
}
