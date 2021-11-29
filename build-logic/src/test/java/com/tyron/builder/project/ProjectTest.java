package com.tyron.builder.project;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class ProjectTest {

    private static final File ROOT = new File("C:/Users/bounc/AndroidStudioProjects/CodeAssist");
    @Test
    public void init() throws IOException {
        Project project = new ProjectImpl(ROOT);
        project.open();
    }
}
