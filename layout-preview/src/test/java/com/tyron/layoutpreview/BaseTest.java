package com.tyron.layoutpreview;

import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.layoutpreview.inflate.PreviewLayoutInflater;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

@RunWith(RobolectricTestRunner.class)
public abstract class BaseTest {

    private static File sResDirectory = new File(getResourcesDirectory(), "test_res");

    private MockAndroidModule mProject;
    protected PreviewLayoutInflater mInflater;

    @Before
    public void setup() throws ExecutionException, InterruptedException {
        mProject = new MockAndroidModule(null, new MockFileManager(null));
        mProject.setAndroidResourcesDirectory(sResDirectory);
        mInflater = new PreviewLayoutInflater(null, mProject);
        mInflater.parseResources(Executors.newSingleThreadExecutor())
                .get();
    }

    private static File getResourcesDirectory()  {
        File currentDirFile = Paths.get(".").toFile();
        String helper = currentDirFile.getAbsolutePath();
        String currentDir = helper.substring(0,
                helper.length() - 1);
        return new File(new File(currentDir), "src/test/resources");
    }
}
