package com.tyron.builder.compiler;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.builder.compiler.incremental.kotlin.IncrementalKotlinCompiler;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AndroidAppKotlinBuilderTest extends AndroidAppBuilderTestBase {

    @Test
    public void testCompile() throws IOException, CompilationFailedException {
        mProject.addKotlinFile(new File(mProject.getJavaDirectory(),
                "com/tyron/test/KotlinClass.kt"));
        mProject.open();

        AndroidAppBuilder builder = new AndroidAppBuilder(null, mProject, ILogger.STD_OUT);
        builder.build(BuildType.RELEASE);

        assertThat(builder.getTasksRan().stream()
                .anyMatch(c -> c instanceof IncrementalKotlinCompiler))
                .isTrue();
    }
}
