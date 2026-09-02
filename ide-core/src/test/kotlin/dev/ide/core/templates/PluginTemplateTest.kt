package dev.ide.core.templates

import dev.ide.core.IdeServices
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.PLUGIN_SPI_VERSION
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin template has to scaffold a project the IDE would actually load, so the checks here are the same
 * ones discovery makes: the generated manifest is parsed by [PluginManifestToml] (the only reader of that
 * file at runtime), and the entry point it names has to be the class that was generated. A template that
 * emitted a manifest the loader rejects would otherwise look fine until someone installed the built APK.
 */
class PluginTemplateTest {

    private fun args(vararg extra: Pair<String, String>) =
        mapOf(TemplateArgs.NAME to NAME, TemplateArgs.PACKAGE to PKG) + extra

    @Test
    fun `scaffolds a manifest the loader accepts`() {
        withTempDir("plugin-template") { dir ->
            IdeServices.createProjectAt(
                dir, TEMPLATE, args(), IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use {
                val manifest = PluginManifestToml.parse(dir.resolve(TOML).readText())

                // The plugin id defaults to the package, and the entry point must be the generated class.
                assertEquals(PKG, manifest.id)
                assertEquals(NAME, manifest.name)
                assertEquals(listOf("$PKG.MyToolPlugin"), manifest.entryPoints)
                // A mismatch here is rejected at load, so the template must track the host's own constant.
                assertEquals(PLUGIN_API_VERSION, manifest.apiVersion)

                val entryPoint = dir.resolve("plugin/src/main/kotlin/com/example/mytool/MyToolPlugin.kt")
                assertTrue(Files.exists(entryPoint), "the class named by entryPoints was not generated")
                assertTrue("class MyToolPlugin : Plugin" in entryPoint.readText())
            }
        }
    }

    @Test
    fun `an explicit plugin id overrides the package`() {
        withTempDir("plugin-template-id") { dir ->
            IdeServices.createProjectAt(
                dir, TEMPLATE, args("pluginId" to "com.example.Custom"),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use {
                val manifest = PluginManifestToml.parse(dir.resolve(TOML).readText())
                // Mixed case is legal in an id and must survive verbatim: it is compared exactly.
                assertEquals("com.example.Custom", manifest.id)
            }
        }
    }

    @Test
    fun `the marker activity and its manifest resource are wired up`() {
        withTempDir("plugin-template-marker") { dir ->
            IdeServices.createProjectAt(
                dir, TEMPLATE, args(), IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use {
                val androidManifest = dir.resolve("plugin/src/main/AndroidManifest.xml").readText()
                // Without all three of these the package manager query never finds the app.
                assertTrue("dev.ide.codeassist.action.PLUGIN" in androidManifest, "missing marker action")
                assertTrue("dev.ide.codeassist.plugin.manifest" in androidManifest, "missing meta-data key")
                assertTrue("@raw/codeassist_plugin" in androidManifest, "meta-data does not point at the manifest")
                assertTrue("android:exported=\"true\"" in androidManifest, "the marker activity must be exported")
            }
        }
    }

    @Test
    fun `what the manifest declares is what the entry point registers`() {
        for ((contributes, expected) in listOf(
            "command" to listOf("ui.action"),
            "settings" to listOf("ui.settingsPage"),
            "both" to listOf("ui.action", "ui.settingsPage"),
        )) {
            withTempDir("plugin-template-$contributes") { dir ->
                IdeServices.createProjectAt(
                    dir, TEMPLATE, args("contributes" to contributes),
                    IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
                ).use {
                    val manifest = PluginManifestToml.parse(dir.resolve(TOML).readText())
                    assertEquals(expected, manifest.capabilities, "capabilities for '$contributes'")

                    val source = dir.resolve("plugin/src/main/kotlin/com/example/mytool/MyToolPlugin.kt").readText()
                    val registersAction = "UI_ACTION_EP" in source
                    val registersPage = "SETTINGS_PAGE_EP" in source
                    assertEquals("ui.action" in expected, registersAction, "action registration for '$contributes'")
                    assertEquals("ui.settingsPage" in expected, registersPage, "page registration for '$contributes'")
                }
            }
        }
    }

    @Test
    fun `the SPI is declared compileOnly at the published coordinates`() {
        val deps = CodeAssistPluginTemplate.dependencies(TemplateArgs(args()))
        assertEquals(
            listOf(
                "io.github.tyron12233:plugin-api:$PLUGIN_SPI_VERSION",
                "io.github.tyron12233:platform-core:$PLUGIN_SPI_VERSION",
            ),
            deps.map { it.coordinate },
        )
        // compileOnly, because the IDE provides these at runtime through the parent classloader.
        assertTrue(deps.all { it.scope == "compileOnly" }, "the SPI must not be bundled into the plugin APK")
        assertTrue(deps.all { it.module == "plugin" }, "declared against the generated module")
    }

    private companion object {
        const val TEMPLATE = "codeassist-plugin"
        const val NAME = "My Tool"
        const val PKG = "com.example.mytool"
        const val TOML = "plugin/src/main/res/raw/codeassist_plugin.toml"
    }
}
