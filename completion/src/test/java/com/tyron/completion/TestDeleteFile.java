package com.tyron.completion;

import static com.google.common.truth.Truth.assertThat;
import static com.tyron.completion.TestUtil.*;

import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class TestDeleteFile {

    private File mMainClass;
    private File mClassToDelete;

    private AndroidModule mProject;
    private FileManager mFileManager;
    private Set<File> mJavaFiles;
    private JavaCompilerService mService;

    private File mRoot;

    @Before
    public void setup() throws IOException {
        CompletionModule.setAndroidJar(new File(resolveBasePath(), "classpath/rt.jar"));
        CompletionModule.setLambdaStubs(new File(resolveBasePath(), "classpath/core-lambda-stubs.jar"));

        mRoot = new File(resolveBasePath(), "EmptyProject");
        mFileManager = new MockFileManager(mRoot);
        mProject = new MockAndroidModule(mRoot, mFileManager);

        mMainClass = new File(resolveBasePath(), "EmptyProject/classes/Main.java");
        mClassToDelete = new File(resolveBasePath(), "EmptyProject/classes/MainSecond.java");

        mJavaFiles = new HashSet<>();
        mJavaFiles.add(mMainClass);
        mJavaFiles.add(mClassToDelete);

        mJavaFiles.forEach(mProject::addJavaFile);
    }

    @Test
    public void test() {
        mService = getNewService(mJavaFiles);

        try (CompileTask task = mService.compile(mMainClass.toPath())) {
            assertThat(task.diagnostics)
                    .isEmpty();
        }

        mProject.removeJavaFile("com.test.MainSecond");
        mJavaFiles.remove(mClassToDelete);
        mService = getNewService(mJavaFiles);

        try(CompileTask task = mService.compile(mMainClass.toPath())) {
            assertThat(task.diagnostics)
                    .isNotEmpty();
        }
    }

    private JavaCompilerService getNewService(Set<File> paths) {
        return new JavaCompilerService(mProject, paths,
                Collections.emptySet(), Collections.emptySet());
    }
}
