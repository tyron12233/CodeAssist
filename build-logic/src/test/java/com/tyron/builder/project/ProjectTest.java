package com.tyron.builder.project;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.project.api.Project;
import com.tyron.builder.project.impl.ProjectImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ProjectTest {

    private Project mProject;

    @Before
    public void setupProject() {
        String rootPath = getClass().getClassLoader()
                .getResource("TestProject")
                .getFile();
        File rootFile = new File(rootPath);
        mProject = new ProjectImpl(rootFile);
    }

    @Test
    public void init() throws IOException {
        mProject.open();

        ManifestData manifestData = mProject.getUserData(CommonProjectKeys.MANIFEST_DATA_KEY);
        assertThat(manifestData).isNotNull();

        List<File> javaFiles = mProject.getUserData(CommonProjectKeys.JAVA_FILES_KEY);
        assertThat(javaFiles).isNotEmpty();
        assertThat(javaFiles).hasSize(1);
    }
}
