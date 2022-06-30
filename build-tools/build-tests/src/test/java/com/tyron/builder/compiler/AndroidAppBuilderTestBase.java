package com.tyron.builder.compiler;

import com.android.tools.aapt2.Aapt2Jni;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.common.TestUtil;

import org.junit.Before;

import java.io.File;

public class AndroidAppBuilderTestBase {

    protected MockFileManager mFileManager;
    protected MockAndroidModule mProject;
    protected File mResourcesDir;

    @Before
    public void setup() throws Exception {
        mResourcesDir = TestUtil.getResourcesDirectory();
        File projectDir = new File(mResourcesDir, "TestProject");

        mFileManager = new MockFileManager(projectDir);
        mProject = new MockAndroidModule(new File(projectDir, "app"), mFileManager);
        mProject.setLambdaStubsJarFile(new File(mResourcesDir, "bootstraps/core-lambda-stubs.jar"));
        mProject.setBootstrapFile(new File(mResourcesDir, "bootstraps/rt.jar"));


        File aapt2;
        if (TestUtil.isWindows()) {
            aapt2 = new File(mResourcesDir, "aapt2/aapt2.exe");
        } else {
            aapt2 = new File(mResourcesDir, "aapt2/libaapt2.so");
            assert aapt2.setExecutable(true);
        }
        Aapt2Jni.setAapt2Binary(aapt2);

        ApkSigner.setTestCertFile(new File(mResourcesDir, "apksigner/testkey.x509.pem"));
        ApkSigner.setTestKeyFile(new File(mResourcesDir, "apksigner/testkey.pk8"));
    }
}
