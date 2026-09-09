package dev.ide.core

import dev.ide.plugin.PLUGIN_API_VERSION
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Editor diagnostics for a plugin's packaged manifest, end to end through the real engine.
 *
 * A `codeassist_plugin.toml` has no language of its own and lives under `res/raw`, which is not a source
 * root, so the first thing worth proving is that it is analysed at all rather than silently treated as an
 * inert asset. The rest asserts the checks that matter: the mistakes here are only otherwise discovered by
 * whoever installs the built plugin.
 */
class PluginManifestInspectionTest {

    private val root = createTempDirectory("plugin-manifest-inspection")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `flags an id the loader would reject`() {
        val s = boot(markerActivity = true)
        // A space is not legal in an id, and discovery drops such a plugin without listing it.
        val text = manifest(id = "com example")
        val file = write(s, text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("must be letters, digits" in problem.message, problem.message)
        // The squiggle belongs on the id's value, not the whole file.
        val underlined = text.substring(problem.range.start, problem.range.end)
        assertEquals("\"com example\"", underlined)
    }

    @Test
    fun `flags an api version this IDE does not load`() {
        val s = boot(markerActivity = true)
        val text = manifest(apiVersion = 99)
        val file = write(s, text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("plugin API 99" in problem.message, problem.message)
        assertEquals("99", text.substring(problem.range.start, problem.range.end))
    }

    @Test
    fun `flags a missing marker activity, which would make the plugin invisible`() {
        val s = boot(markerActivity = false)
        val text = manifest()
        val file = write(s, text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("AndroidManifest.xml is missing" in problem.message, problem.message)
        assertTrue("dev.ide.codeassist.action.PLUGIN" in problem.message, problem.message)
        // Reported against the table header, since the file as a whole is what cannot be discovered.
        assertEquals("[plugin]", text.substring(problem.range.start, problem.range.end))
    }

    @Test
    fun `a well-formed manifest reports nothing`() {
        val s = boot(markerActivity = true)
        val text = manifest()
        val file = write(s, text)

        assertEquals(
            emptyList(),
            runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message },
        )
    }

    @Test
    fun `another toml in the same tree is left alone`() {
        val s = boot(markerActivity = true)
        // Not a plugin manifest, so none of these checks apply however little sense it makes as one.
        val file = root.resolve("app/src/main/res/raw/other.toml")
        Files.createDirectories(file.parent)
        val text = "id = \"nonsense value\"\n"
        Files.writeString(file, text)
        s.modules()

        assertEquals(emptyList(), runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message })
    }

    @Test
    fun `flags a misspelled capability, which the consent screen would show as written`() {
        val s = boot(markerActivity = true)
        val text = manifest(capabilities = listOf("ui.toolwindow"))
        val file = write(s, text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("Unknown capability 'ui.toolwindow'" in problem.message, problem.message)
        // The known set is in the message, since the fix is almost always one character away from it.
        assertTrue("ui.toolWindow" in problem.message, problem.message)
    }

    @Test
    fun `flags a capability the plugin has no facet to deliver`() {
        val s = boot(markerActivity = true)
        // An engine-only manifest claiming a panel: nothing in it can contribute one.
        val text = manifest(capabilities = listOf("ui.toolWindow"))
        val file = write(s, text)

        val problem = runBlocking { s.analyzeDiagnostics(file, text) }.single()
        assertTrue("needs a UI facet" in problem.message, problem.message)
    }

    @Test
    fun `a capability its facet can deliver is not flagged`() {
        val s = boot(markerActivity = true)
        val text = manifest(capabilities = listOf("ui.action", "fs.read"))
        val file = write(s, text)

        assertEquals(emptyList(), runBlocking { s.analyzeDiagnostics(file, text) }.map { it.message })
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun manifest(
        id: String = "com.example.app.plugin",
        apiVersion: Int = PLUGIN_API_VERSION,
        capabilities: List<String> = emptyList(),
    ): String = """
        [plugin]
        id = "$id"
        name = "Demo"
        version = "1.0.0"
        apiVersion = $apiVersion
        entryPoints = ["com.example.app.DemoPlugin"]
    """.trimIndent() + "\n" +
        if (capabilities.isEmpty()) "" else "capabilities = [${capabilities.joinToString(", ") { "\"$it\"" }}]\n"

    private fun boot(markerActivity: Boolean): IdeServices {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        if (markerActivity) addMarkerActivity()
        return s
    }

    /** Put the discovery marker into the demo app's Android manifest, as a real plugin app would have. */
    private fun addMarkerActivity() {
        val androidManifest = root.resolve("app/src/main/AndroidManifest.xml")
        val xml = androidManifest.readText()
        Files.writeString(
            androidManifest,
            xml.replace(
                "</application>",
                """
                    <activity android:name=".PluginInfoActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="dev.ide.codeassist.action.PLUGIN" />
                        </intent-filter>
                        <meta-data android:name="dev.ide.codeassist.plugin.manifest"
                                   android:resource="@raw/codeassist_plugin" />
                    </activity>
                </application>
                """.trimIndent(),
            ),
        )
    }

    private fun write(services: IdeServices, text: String): Path {
        val file = root.resolve("app/src/main/res/raw/codeassist_plugin.toml")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        services.modules()
        return file
    }
}
