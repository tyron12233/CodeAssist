package dev.ide.core

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DECLARATION_PROVIDER_EP
import dev.ide.analysis.DeclarationProvider
import dev.ide.analysis.NavigationTarget
import dev.ide.core.backend.toPluginActionContext
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lang.kotlin.NavKind
import dev.ide.model.FacetData
import dev.ide.model.WORKSPACE_SERVICE
import dev.ide.platform.PluginId
import dev.ide.ui.backend.UiActionContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host halves of what SPI 3.1.0 gives a plugin: go-to-declaration it contributes, a facet it can switch
 * on in a module and have saved, and the workspace an action is handed.
 */
class PluginSpi31Test {

    private val root = createTempDirectory("plugin-spi-31")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** Answers for the word `jump` with a fixed place in another file; nothing anywhere else. */
    private class JumpProvider(private val to: String) : DeclarationProvider {
        override val id = "test.jump"
        override val languages = setOf(LanguageId("widget"))

        override suspend fun declarations(target: AnalysisTarget, offset: Int): List<NavigationTarget> {
            val text = target.parsed.text().toString()
            val at = text.indexOf("jump")
            return if (at >= 0 && offset in at..at + 4) listOf(NavigationTarget(to, 7, "the other end")) else emptyList()
        }
    }

    private fun bootstrap(): IdeServices {
        val env = ApplicationEnvironment()
        env.platform.extensions.register(
            FILE_TYPE_EP, FileTypeMapping(listOf(".widget"), LanguageId("widget")), PluginId("test-widget"),
        )
        val other = root.resolve("app/src/main/java/com/example/app/Other.java").toString()
        env.platform.extensions.register(DECLARATION_PROVIDER_EP, JumpProvider(other), PluginId("test-widget"))
        return IdeServices.bootstrapJavaDemo(root, env).also { services = it }
    }

    @Test
    fun aDeclarationProviderAnswersGoToDeclaration() {
        val s = bootstrap()
        val text = "please jump here\n"
        val file = write(s, "thing.widget", text)

        val targets = s.navigationTargets(file, text, text.indexOf("jump") + 1, NavKind.DECLARATION)
        assertEquals(listOf("Other.java" to 7), targets.map { it.file.path.substringAfterLast('/') to it.offset })

        val options = s.navigationOptions(file, text, text.indexOf("jump") + 1)
        assertTrue(options.any { (kind, list) -> kind == NavKind.DECLARATION && list.single().label == "the other end" })

        assertTrue(
            s.navigationTargets(file, text, text.indexOf("here"), NavKind.DECLARATION).isEmpty(),
            "a provider answers only where it has something",
        )
    }

    @Test
    fun aFacetSetThroughModuleSourcesIsSavedToTheModule() {
        val s = bootstrap()
        val module = s.modules().first()
        assertTrue(s.moduleService.setFacetData(module.name, FacetData("ndk", mapOf("libraryName" to "engine"))))
        val toml = Files.walk(root).use { walk ->
            walk.filter { it.fileName.toString() == "module.toml" && "[ndk]" in it.readText() }.findFirst().orElse(null)
        }
        assertNotNull(toml, "module.toml carries the new table")
        assertTrue("libraryName = \"engine\"" in toml.readText(), toml.readText())
    }

    @Test
    fun anActionIsHandedTheWorkspace() {
        val s = bootstrap()
        val ctx = toPluginActionContext(UiActionContext(place = "fileContext"), root.toString(), s.store.workspaceContainer)
        val workspace = assertNotNull(ctx.workspaceServices.getServiceOrNull(WORKSPACE_SERVICE))
        assertEquals(s.modules().map { it.name }, workspace.projects.flatMap { it.modules }.map { it.name })
    }

    private fun write(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        services.modules()
        return file
    }
}
