package dev.ide.core.plugins

import dev.ide.core.IdeServices
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a module is a CodeAssist plugin, which is decided by the presence of the packaged manifest rather
 * than by anything recorded in `module.toml`. Two features read this answer — the editor checks on the
 * manifest and the Run row's "Install plugin" label — so it is worth pinning independently of both.
 */
class PluginProjectTest {

    private val root = createTempDirectory("plugin-project")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `an ordinary app module is not a plugin`() {
        val app = boot()
        assertFalse(PluginProject.isPluginModule(app))
        assertNull(PluginProject.manifestIn(app))
    }

    @Test
    fun `packaging the manifest is what makes it one`() {
        val app = boot()
        val manifest = writeManifest()

        // Detected from disk, so no re-import or model edit is needed for the IDE to notice.
        assertTrue(PluginProject.isPluginModule(app))
        assertEquals(manifest, PluginProject.manifestIn(app))
    }

    @Test
    fun `a toml under res-raw with another name is not the marker`() {
        val app = boot()
        val other = root.resolve("app/src/main/res/raw/settings.toml")
        Files.createDirectories(other.parent)
        Files.writeString(other, "id = \"x\"\n")

        assertFalse(PluginProject.isPluginModule(app))
    }

    @Test
    fun `the Android manifest is found beside the res root that owns the file`() {
        val app = boot()
        val manifest = writeManifest()

        val androidManifest = PluginProject.androidManifestBeside(app, manifest)
        assertEquals(root.resolve("app/src/main/AndroidManifest.xml"), androidManifest)
    }

    @Test
    fun `a file outside the module's res roots resolves to no Android manifest`() {
        val app = boot()
        // Nothing to relate it to, so the caller skips rather than guessing a path.
        assertNull(PluginProject.androidManifestBeside(app, root.resolve("elsewhere/codeassist_plugin.toml")))
    }

    private fun boot() = IdeServices.bootstrapDemo(root)
        .also { services = it }
        .modules()
        .first { it.type.id == "android-app" }

    private fun writeManifest() =
        root.resolve("app/src/main/res/raw/${PluginProject.MANIFEST_NAME}").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "[plugin]\nid = \"com.example.p\"\nentryPoints = [\"com.example.P\"]\n")
        }
}
