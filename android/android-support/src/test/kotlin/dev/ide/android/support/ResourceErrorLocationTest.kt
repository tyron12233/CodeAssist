package dev.ide.android.support

import dev.ide.android.support.tasks.ResourceMergeMap
import dev.ide.android.support.tasks.mergeResourceDirs
import dev.ide.android.support.tools.DebugKeystore
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.VariantSelector
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.model.BuildSystemId
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.open
import dev.ide.testkit.testEnv
import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An aapt2 error in an app's resources names the user's own file, not the build's merged copy of it. The app's
 * resources are merged into one `merged-res` dir before aapt2 sees them, so its errors named that copy, and a
 * user who opened the error and fixed it there changed nothing: the next build merged the source over it again.
 */
class ResourceErrorLocationTest {

    @Test
    fun aMergedValuesErrorMapsBackToItsSourceLine() {
        testEnv("res-merge-map") { env ->
            val dir = env.dir
            val lib = dir.writeSource("lib/res/values/values.xml", LIB_VALUES)
            val own = dir.writeSource("app/res/values/strings.xml", APP_STRINGS)
            val merged = dir.resolve("out/merged-res")
            mergeResourceDirs(listOf(lib.parent.parent, own.parent.parent), merged).save(ResourceMergeMap.fileFor(merged))

            val mergedValues = merged.resolve("values/values.xml")
            val mergedLine = 1 + Files.readAllLines(mergedValues).indexOfFirst { "name=\"broken\"" in it }
            val map = ResourceMergeMap.load(ResourceMergeMap.fileFor(merged))!!

            val out = map.remap(
                listOf(
                    "$mergedValues:$mergedLine: error: resource string/missing (aka com.example.app:string/missing) not found.",
                    "error: failed linking references.",
                ),
            )

            assertEquals("$own:4: error: resource string/missing (aka com.example.app:string/missing) not found.", out[0])
            assertEquals("error: failed linking references.", out[1], "a line naming no merged file is left alone")
        }
    }

    @Test
    fun anAppResourceErrorNamesTheUsersFile() {
        val sdk = assumeAndroidSdk()

        testEnv("res-error-location") { env ->
            val dir = env.dir
            val store = ProjectModel.open(dir, env.platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(env.platform.extensions)
            AndroidSupport.register(types, FacetCodecRegistry())
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", types.resolve("android-app")).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    putFacet(AndroidFacet(namespace = "com.example.app", compileSdk = 34, minSdk = 24, targetSdk = 34))
                }
                commit()
            }
            dir.writeSource("app/src/main/AndroidManifest.xml", APP_MANIFEST)
            val strings = dir.writeSource("app/src/main/res/values/strings.xml", APP_STRINGS)

            val signing = DebugKeystore.getOrCreate(dir.resolve(".keystore/debug.ks"), sdk.keytool)
            val graph = AndroidBuildSystem.inProcess(sdk, signing).createBuildGraph(
                store.workspace.projects.single(),
                BuildRequest(listOf(dev.ide.model.ModuleId("app")), VariantSelector("debug"), BuildGoal.COMPILE_ONLY),
            )
            val log = StringBuilder()
            val outcome = runBlocking {
                TaskExecutorImpl(BuildCache(dir.resolve(".caches/build"))).execute(graph, SimpleTaskContext(log = { log.appendLine(it) }), 2)
            }

            assertFalse(outcome.succeeded, "the broken reference should fail the link:\n$log")
            assertTrue("${strings.toAbsolutePath().normalize()}:4: error" in log, "the error should name the user's strings.xml:\n$log")
            assertFalse(log.lines().any { "merged-res" in it && "error:" in it }, "no error should name the merged copy:\n$log")
        }
    }

    private companion object {
        val LIB_VALUES = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="from_lib">Lib</string>
            </resources>
        """
        // `broken` sits on line 4: the error has to come back to exactly that line.
        val APP_STRINGS = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">App</string>
                <string name="broken">@string/missing</string>
            </resources>
        """
        val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="@string/app_name"/>
            </manifest>
        """
    }
}
