package com.tyron.builder.project;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.Project;
import com.tyron.builder.project.experimental.AndroidModule;
import com.tyron.builder.project.impl.FileManagerImpl;
import com.tyron.builder.project.impl.ProjectImpl;

import org.json.JSONException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class ProjectTest {

    private FileManager mFileManager;
    private Project mProject;
    private File mRoot;

    @Before
    public void setupProject() {
        String rootPath = getClass().getClassLoader()
                .getResource("TestProject")
                .getFile();
        mRoot = new File(rootPath);
        mFileManager = new FileManagerImpl(mRoot);
        mProject = new ProjectImpl(mRoot);
    }

    @Test
    public void init() throws IOException, JSONException {
        AndroidProjectManager projectManager = new AndroidProjectManager(mRoot);
        projectManager.initialize();
        assertThat(projectManager.getModules())
                .hasSize(2);

        Module module = projectManager.getModules().get(0);
        assertThat(module)
                .isInstanceOf(AndroidModule.class);

        ManifestData data = module.getData(AndroidModule.MANIFEST_DATA_KEY);
        assertThat(data).isNotNull();
        assertThat(data.getPackage())
                .isEqualTo("com.tyron.test");

        Module module2 = projectManager.getModules().get(1);
        assertThat(module2).isNotNull();

        data = module2.getData(AndroidModule.MANIFEST_DATA_KEY);
        assertThat(data).isNotNull();
        assertThat(data.getPackage())
                .isEqualTo("com.tyron.module2");

        System.out.println(module.getDependingModules());
    }
}