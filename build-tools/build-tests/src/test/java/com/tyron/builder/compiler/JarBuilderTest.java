package com.tyron.builder.compiler;

import com.google.common.truth.Truth;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.builder.project.mock.MockJavaModule;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class JarBuilderTest {

    private MockFileManager mFileManager;
    private MockJavaModule mJavaModule;
    protected File mResourcesDir;

    @Before
    public void setup() throws Exception {
        mResourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(mResourcesDir, "TestProject");

        mFileManager = new MockFileManager(projectDir);
        mJavaModule = new MockJavaModule(new File(projectDir, "module2"), mFileManager);
        mJavaModule.setLambdaStubsJarFile(new File(mResourcesDir, "bootstraps/core-lambda-stubs.jar"));
        mJavaModule.setBootstrapFile(new File(mResourcesDir, "bootstraps/rt.jar"));
    }

    @Test
    public void testJar() throws IOException, CompilationFailedException {
        mJavaModule.addJavaFile(new File(mJavaModule.getRootFile(), "src/main/java/Test.java"));
        mJavaModule.open();

        JarBuilder builder = new JarBuilder(null, mJavaModule, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        File jarFile = new File(mJavaModule.getBuildDirectory(), "bin/classes.jar");
        Truth.assertThat(jarFile.exists())
                .isTrue();
    }
}
