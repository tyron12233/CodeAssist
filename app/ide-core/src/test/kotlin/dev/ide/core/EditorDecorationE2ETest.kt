package dev.ide.core

import dev.ide.platform.PluginId
import dev.ide.plugin.editor.DecorationStyle
import dev.ide.plugin.editor.DecorationTint
import dev.ide.plugin.editor.EDITOR_DECORATION_EP
import dev.ide.plugin.editor.EditorDecorationContext
import dev.ide.plugin.editor.EditorDecorationProvider
import dev.ide.plugin.editor.EditorDecorations
import dev.ide.plugin.editor.EditorInlay
import dev.ide.plugin.editor.GutterMark
import dev.ide.plugin.editor.InlayKind
import dev.ide.plugin.editor.TextDecoration
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiDecorationStyle
import dev.ide.ui.backend.UiDecorationTint
import dev.ide.ui.backend.UiEditorDecorations
import dev.ide.ui.backend.UiInlayKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plugin's editor decorations from registration to the UI DTO: the whole seam
 * `EDITOR_DECORATION_EP` -> `EditorDecorationCollector` -> `IdeServices.editorDecorations` ->
 * `EditorService.decorations`, which is what the editor's decoration pass calls.
 */
class EditorDecorationE2ETest {

    private val plugin = PluginId("coverage-test")

    private class CoverageProvider(
        private val onlyKotlin: Boolean = false,
    ) : EditorDecorationProvider {
        override val id = "coverage"
        var lastLanguageId: String? = null
            private set

        override fun appliesTo(ctx: EditorDecorationContext): Boolean {
            lastLanguageId = ctx.languageId
            return !onlyKotlin || ctx.languageId == "kotlin"
        }

        override suspend fun decorate(ctx: EditorDecorationContext) = EditorDecorations(
            ranges = listOf(
                TextDecoration(0, 7, DecorationStyle.Background, DecorationTint.Success, tooltip = "covered"),
            ),
            gutter = listOf(
                GutterMark(0, "check", DecorationTint.Success, tooltip = "100%", actionId = "coverage.open"),
            ),
            inlays = listOf(EditorInlay(7, "100%", InlayKind.Other)),
        )
    }

    /** Decorations for [relPath] under the demo project, with [providers] registered before the engine opens. */
    private fun decorationsFor(
        dir: Path,
        relPath: String,
        vararg providers: EditorDecorationProvider,
    ): UiEditorDecorations {
        val env = ApplicationEnvironment()
        for (p in providers) env.platform.extensions.register(EDITOR_DECORATION_EP, p, plugin)
        return IdeServices.bootstrapJavaDemo(dir, env).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val file = when {
                relPath.startsWith("/") -> dir.resolve(relPath.removePrefix("/"))
                else -> ide.sourceRoots(app).first().resolve(relPath)
            }
            runBlocking {
                IdeServicesBackend(ide).editor.decorations(file.toString(), "package com.example.app; class Probe {}")
            }
        }
    }

    @Test
    fun aRegisteredProvidersMarksReachTheEditorAsDtos() {
        withTempDir("ide-deco-e2e") { dir ->
            val provider = CoverageProvider()
            val result = decorationsFor(dir, "com/example/app/Probe.java", provider)

            result.ranges.single().let {
                assertEquals(0 to 7, it.startOffset to it.endOffset)
                assertEquals(UiDecorationStyle.Background, it.style)
                assertEquals(UiDecorationTint.Success, it.tint)
                assertEquals("covered", it.tooltip)
            }
            result.gutter.single().let {
                assertEquals(0, it.line)
                assertEquals("check", it.iconId)
                assertEquals("coverage.open", it.actionId, "the tap target survives the crossing")
            }
            result.inlays.single().let {
                assertEquals(7, it.offset)
                assertEquals("100%", it.text)
                assertEquals(UiInlayKind.Other, it.kind)
            }
            assertEquals("java", provider.lastLanguageId, "the provider is told what language it is looking at")
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun noRegisteredProviderIsTheEmptyAnswer() {
        withTempDir("ide-deco-none") { dir ->
            assertTrue(decorationsFor(dir, "com/example/app/Probe.java").isEmpty)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aProviderThatDoesNotClaimTheLanguageContributesNothing() {
        withTempDir("ide-deco-gate") { dir ->
            val result = decorationsFor(dir, "com/example/app/Probe.java", CoverageProvider(onlyKotlin = true))
            assertTrue(result.isEmpty, "a Kotlin-only provider says nothing about a Java file")
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aFileOutsideEverySourceRootIsStillDecorated() {
        withTempDir("ide-deco-loose") { dir ->
            // A loose file no module owns. The analyzer-backed passes give up on one of these; a decorating
            // plugin is not a language, so it must not.
            val result = decorationsFor(dir, "/notes.txt", CoverageProvider())
            assertEquals(1, result.gutter.size, "a plugin can mark a file no module claims")
            dir.toFile().deleteRecursively()
        }
    }
}
