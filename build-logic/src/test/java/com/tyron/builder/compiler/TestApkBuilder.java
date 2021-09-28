package com.tyron.builder.compiler;

import com.tyron.builder.BuildModule;
import com.tyron.builder.TestProject;
import com.tyron.builder.model.Project;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

@RunWith(RobolectricTestRunner.class)
public class TestApkBuilder {

    private static final String PROJECT_NAME = "TestProject";

    private Project mProject;
    private ApkBuilder mApkBuilder;

    @Before
    public void setup() {
        BuildModule.initialize(ApplicationProvider.getApplicationContext());
        mProject = new TestProject(PROJECT_NAME).getProject();
    }

    @Test
    public void testBuild() {

    }
}
