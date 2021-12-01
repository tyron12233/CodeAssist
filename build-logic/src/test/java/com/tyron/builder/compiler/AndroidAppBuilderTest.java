package com.tyron.builder.compiler;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.impl.MockAndroidProject;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class AndroidAppBuilderTest {

    private MockAndroidProject mProject;
    private File mResourcesDir;

    @Before
    public void setup() throws Exception {
        mResourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(mResourcesDir, "TestProject");
        mProject = new MockAndroidProject(projectDir);
        mProject.setLambdaStubsJarFile(new File(mResourcesDir, "bootstraps/core-lambda-stubs.jar"));
        mProject.setBootstrapFile(new File(mResourcesDir, "bootstraps/rt.jar"));


        File aapt2;
        if (TestUtil.isWindows()) {
            aapt2 = new File(mResourcesDir, "aapt2/aapt2.exe");
        } else {
            aapt2 = new File(mResourcesDir, "aapt2/libaapt2.so");
            assert aapt2.setExecutable(true);
        }
        IncrementalAapt2Task.setAapt2Binary(aapt2);
    }

    @Test
    public void testBuild() throws Exception {
        ApkSigner.setTestCertFile(new File(mResourcesDir, "apksigner/testkey.x509.pem"));
        ApkSigner.setTestKeyFile(new File(mResourcesDir, "apksigner/testkey.pk8"));

        mProject.addJavaFile(new File(mProject.getJavaDirectory(),
                "com/tyron/test/MainActivity.java"));
        mProject.open();

        AndroidAppBuilder builder = new AndroidAppBuilder(mProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        File signedApk = new File(mProject.getBuildDirectory(), "bin/signed.apk");
        assertThat(signedApk.exists()).isTrue();

        FileUtils.deleteDirectory(mProject.getBuildDirectory());
    }
}