package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.model.SdkResolution
import dev.ide.testkit.TestJars
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An installed Android platform becomes an `android-<level>` SDK in the workspace table when a project opens,
 * and an Android module whose compileSdk matches resolves it: the editor's boot classpath (and the build's,
 * which reads the same table) is that platform's `android.jar`, and Module Settings reports nothing missing.
 * A platform removed from the SDK leaves the table on the next open.
 */
class PlatformSdkSyncTest {

    @Test
    fun anInstalledPlatformIsWhatItsCompileSdkModulesCompileAgainst() {
        withTempDir("platform-sdk-sync") { dir ->
            val sdkRoot = Files.createTempDirectory("fake-android-sdk")
            try {
                val compileSdk = IdeServices.bootstrapDemo(dir).use { ide -> appFacet(ide).compileSdk }
                // The SDK Manager's root is the project's `local.properties` sdk.dir, as for a Gradle project.
                Files.writeString(dir.resolve("local.properties"), "sdk.dir=$sdkRoot\n")
                val jar = platformJar(sdkRoot, compileSdk)

                IdeServices.open(dir).use { ide ->
                    val app = ide.modules().first { it.name == "app" }
                    val sdk = assertNotNull(ide.store.workspace.sdkTable.byName("android-$compileSdk"), "platform registered")
                    assertEquals("android-$compileSdk", SdkResolution.sdkFor(ide.store.workspace, app)?.name)
                    assertEquals(jar.toRealPath(), Path.of(sdk.bootClasspath.first().path).toRealPath())
                    assertEquals(jar.toRealPath(), ide.bootClasspathFor(app).first().toRealPath(), "the build's boot classpath")
                    assertNull(ide.moduleService.getModuleConfig("app")!!.missingPlatform)
                }

                Files.walk(sdkRoot.resolve("platforms")).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                IdeServices.open(dir).use { ide ->
                    assertNull(ide.store.workspace.sdkTable.byName("android-$compileSdk"), "a removed platform is dropped")
                    val app = ide.modules().first { it.name == "app" }
                    assertTrue(SdkResolution.sdkFor(ide.store.workspace, app)?.name != "android-$compileSdk")
                }
            } finally {
                sdkRoot.toFile().deleteRecursively()
                dir.toFile().deleteRecursively()
            }
        }
    }

    private fun platformJar(sdkRoot: Path, level: Int): Path {
        val dir = Files.createDirectories(sdkRoot.resolve("platforms").resolve("android-$level"))
        return TestJars.buildJar(dir.resolve("android.jar")) { entry("android/Marker.class", ByteArray(0)) }
    }

    private fun appFacet(ide: IdeServices): AndroidFacet =
        assertNotNull(ide.modules().first { it.name == "app" }.facets.get(AndroidFacet.KEY))
}
