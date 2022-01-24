package com.tyron.completion.java;

import static com.google.common.truth.Truth.assertThat;
import static com.tyron.completion.TestUtil.*;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;

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

    private Project mProject;
    private AndroidModule mModule;
    private FileManager mFileManager;
    private Set<File> mJavaFiles;
    private JavaCompilerService mService;

    private File mRoot;

    @Before
    public void setup() throws IOException {
        CompletionModule.setAndroidJar(new File(resolveBasePath(), "classpath/rt.jar"));
        CompletionModule.setLambdaStubs(new File(resolveBasePath(),
                "classpath/core-lambda-stubs" + ".jar"));

        mRoot = new File(resolveBasePath(), "EmptyProject");
        mProject = new Project(mRoot);
        mFileManager = new MockFileManager(mRoot);
        mModule = new MockAndroidModule(new File(mRoot, "app"), mFileManager);

        mMainClass = new File(resolveBasePath(), "EmptyProject/classes/Main.java");
        mClassToDelete = new File(resolveBasePath(), "EmptyProject/classes/MainSecond.java");

        mJavaFiles = new HashSet<>();
        mJavaFiles.add(mMainClass);
        mJavaFiles.add(mClassToDelete);

        mJavaFiles.forEach(mModule::addJavaFile);
    }

    @Test
    public void test() {
        mService = getNewService(mJavaFiles);

        CompilerContainer container = mService.compile(mMainClass.toPath());
        container.run(task -> {
            assertThat(task.diagnostics).isEmpty();
        });

        mModule.removeJavaFile("com.test.MainSecond");
        mJavaFiles.remove(mClassToDelete);
        mService = getNewService(mJavaFiles);

        container = mService.compile(mMainClass.toPath());
        container.run(task -> {
            assertThat(task.diagnostics).isNotEmpty();
        });
    }

    private JavaCompilerService getNewService(Set<File> paths) {
        JavaCompilerService javaCompilerService = new JavaCompilerService(mProject, paths,
                Collections.emptySet(), Collections.emptySet());
        javaCompilerService.setCurrentModule(mModule);
        return javaCompilerService;
    }
}
