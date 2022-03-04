package com.tyron.builder.compiler;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.log.ILogger;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class AndroidAppBuilderTest extends AndroidAppBuilderTestBase {

    @Test
    public void testBuild() throws Exception {
        mProject.addJavaFile(new File(mProject.getJavaDirectory(),
                "com/tyron/test/MainActivity.java"));
        mProject.open();

        AndroidAppBuilder builder = new AndroidAppBuilder(null, mProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        File signedApk = new File(mProject.getBuildDirectory(), "bin/signed.apk");
        assertThat(signedApk.exists()).isTrue();

        FileUtils.deleteQuietly(mProject.getBuildDirectory());
    }
}
