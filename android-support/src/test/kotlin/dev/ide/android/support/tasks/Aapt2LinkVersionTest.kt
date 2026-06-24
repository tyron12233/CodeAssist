package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Aapt2
import dev.ide.android.support.tools.Aapt2CompileResult
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.engine.SimpleTaskContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `aapt2 link` must inject `versionCode`/`versionName` into the APK. A manifest that declares neither
 * (the default template) otherwise ships `versionCode=0`/`versionName=null` — which trips device
 * app-health detectors (the Samsung `IafdService` NOT-NULL crash) and is a malformed-app smell. AGP
 * always injects the `defaultConfig` values; this guards that our pipeline forwards them to the linker.
 * No SDK needed — a fake [Aapt2] records the version it was handed.
 */
class Aapt2LinkVersionTest {

    private class RecordingAapt2 : Aapt2 {
        var versionCode: Int? = null
        var versionName: String? = null
        override fun compile(resDirs: List<Path>, outDir: Path) = Aapt2CompileResult(emptyList(), ToolResult.ok())
        override fun link(
            compiled: List<Path>, manifest: Path, androidJar: Path, customPackage: String,
            extraPackages: List<String>, minSdk: Int, targetSdk: Int, genJavaDir: Path, outApk: Path,
            versionCode: Int?, versionName: String?, nonFinalIds: Boolean,
            proguardRules: Path?, protoFormat: Boolean,
        ): ToolResult {
            this.versionCode = versionCode
            this.versionName = versionName
            return ToolResult.ok()
        }
    }

    @Test
    fun linkForwardsVersionCodeAndName() = runBlocking {
        val tmp = Files.createTempDirectory("aapt2-link-version")
        try {
            val compiledDir = Files.createDirectories(tmp.resolve("compiled"))
            val manifest = tmp.resolve("AndroidManifest.xml")
            Files.writeString(manifest, "<manifest package=\"com.example.app\"/>")
            val androidJar = tmp.resolve("android.jar").also { Files.writeString(it, "") }
            val aapt2 = RecordingAapt2()

            Aapt2LinkTask(
                TaskName(":app:aapt2Link"), compiledDir, manifest, androidJar,
                "com.example.app", emptyList(), 26, 34, 7, "2.3-debug",
                tmp.resolve("gen"), tmp.resolve("resources.ap_"), aapt2,
            ).execute(SimpleTaskContext())

            assertEquals(7, aapt2.versionCode, "versionCode must reach aapt2 link")
            assertEquals("2.3-debug", aapt2.versionName, "versionName (with build-type suffix) must reach aapt2 link")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
