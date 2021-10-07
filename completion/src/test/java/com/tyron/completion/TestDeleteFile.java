package com.tyron.completion;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.truth.Truth;
import com.tyron.builder.parser.FileManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class TestDeleteFile {

    private static final String MODULE_NAME = "completion";

    private File mMainClass;
    private File mClassToDelete;

    private Set<File> mJavaFiles;
    private JavaCompilerService mService;

    @Before
    public void setup() {
        FileManager.getInstance(new File(resolveBasePath(), "classpath/rt.jar"),
                new File(resolveBasePath(), "classpath/core-lambda-stubs.jar"));

        mMainClass = new File(resolveBasePath(), "classes/Main.java");
        mClassToDelete = new File(resolveBasePath(), "classes/MainSecond.java");

        mJavaFiles = new HashSet<>();
        mJavaFiles.add(mMainClass);
        mJavaFiles.add(mClassToDelete);

        mJavaFiles.forEach(FileManager.getInstance()::addJavaFile);
    }

    @Test
    public void test() {
        mService = getNewService(mJavaFiles);

        try (CompileTask task = mService.compile(mMainClass.toPath())) {
            assertThat(task.diagnostics)
                    .isEmpty();
        }

        FileManager.getInstance().removeJavaFile("com.test.MainSecond");
        mJavaFiles.remove(mClassToDelete);
        mService = getNewService(mJavaFiles);

        try(CompileTask task = mService.compile(mMainClass.toPath())) {
            assertThat(task.diagnostics)
                    .isNotEmpty();
        }
    }

    private JavaCompilerService getNewService(Set<File> paths) {
        return new JavaCompilerService(paths,
                Collections.emptySet(), Collections.emptySet());
    }

    public static String resolveBasePath() {
        final String path = "./" + MODULE_NAME + "/src/test/resources";
        if (Arrays.asList(Objects.requireNonNull(new File("./").list())).contains(MODULE_NAME)) {
            return path;
        }
        return "../" + path;
    }
}
