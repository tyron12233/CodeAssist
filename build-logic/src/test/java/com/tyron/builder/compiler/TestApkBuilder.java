package com.tyron.builder.compiler;

import com.tyron.builder.TestProject;
import com.tyron.builder.model.Project;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static com.google.common.truth.Truth.assertThat;

@RunWith(RobolectricTestRunner.class)
public class TestApkBuilder {

    private static final String PROJECT_NAME = "TestProject";

    private Project mProject;
    private ApkBuilder mApkBuilder;

    @Before
    public void setup() {
        mProject = new TestProject(PROJECT_NAME).getProject();

        mApkBuilder = new ApkBuilder(StdLogger.INSTANCE, mProject);
    }

    @Test
    public void testBuild() {
        mApkBuilder.build((success, message) -> {
            assertThat(success).isTrue();
        });
    }
}
