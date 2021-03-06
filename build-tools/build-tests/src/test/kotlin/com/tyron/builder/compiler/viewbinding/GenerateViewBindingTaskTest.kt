package com.tyron.builder.compiler.viewbinding

import com.google.common.truth.Truth.assertThat
import com.tyron.builder.compiler.AndroidAppBuilder
import com.tyron.builder.compiler.AndroidAppBuilderTestBase
import com.tyron.builder.compiler.BuildType
import com.tyron.builder.compiler.viewbinding.GenerateViewBindingTask.Companion.VIEW_BINDING_GEN_DIR
import com.tyron.builder.log.ILogger
import com.tyron.builder.model.ModuleSettings
import org.apache.commons.io.FileUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * See [com.tyron.viewbinding.tool.writer.ViewBinderGenerateJavaTest] for more extensive tests.
 */
@RunWith(RobolectricTestRunner::class)
class GenerateViewBindingTaskTest : AndroidAppBuilderTestBase() {

    @Test
    fun testBuild() {
        // enable ViewBinding
        mProject.settings.edit()
            .putBoolean(ModuleSettings.VIEW_BINDING_ENABLED, true)
            .commit()

        mProject.addJavaFile(File(mProject.javaDirectory, "com/tyron/test/MainActivity.java"))
        mProject.open()

        AndroidAppBuilder(null, mProject, ILogger.STD_OUT)
            .build(BuildType.RELEASE)

        val bindingDir = File(mProject.buildDirectory, VIEW_BINDING_GEN_DIR)
        val bindingClass =
            File(bindingDir, "com/tyron/test/databinding/ActivityMainBinding.java")

        assertThat(
            bindingClass.exists()
        ).isTrue()

        val bindingClassContents = FileUtils.readFileToString(bindingClass, StandardCharsets.UTF_8)
        assertThat(
            bindingClassContents
        ).contains("// Generated by view binder compiler. Do not edit!")
        assertThat(
            bindingClassContents
        ).contains("public final TextView textView;")

        assertThat(
            File(mProject.buildDirectory, "bin/signed.apk").exists()
        ).isTrue()

        FileUtils.deleteQuietly(mProject.buildDirectory)
    }

}
