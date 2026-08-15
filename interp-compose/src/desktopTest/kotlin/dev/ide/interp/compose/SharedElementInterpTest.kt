package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared-element interpreter workstream. Renders REAL lowered Kotlin source through the preview interpreter in an
 * [ImageComposeScene] (real UiApplier + measure + Skiko draw), capturing partial-render errors — the shape a real
 * @Preview takes. Reproduces (and now guards) the Jetsnack shared-element failures headlessly.
 *
 * Root fix under test: a labeled `this@SharedTransitionLayout` resolves to the OUTER receiver, not the innermost
 * `AnimatedVisibility` scope, so `rememberSharedContentState`/`sharedBounds`/`renderInSharedTransitionScopeOverlay`
 * dispatch on the real `SharedTransitionScope`.
 */
class SharedElementInterpTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    /** Lower [code] and render [entry] in an ImageComposeScene; returns the NON-NULL partial-render error messages,
     *  or null if Skiko is unavailable here (then the caller skips). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderErrors(code: String, entry: String): List<String>? {
        val trimmed = code.trimIndent()
        val service = KotlinSymbolService(listOf(MemDir(listOf(MemFile("Main.kt", trimmed)))), classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(trimmed)) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(service)
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val entryFn = program[entry] ?: error("no entry `$entry`; have ${program.keys}")

        val errs = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entryFn, program, classes, emptyList(), onError = {}, onPartialError = { errs.add(it?.message) })
        }
        return try {
            val scene = ImageComposeScene(300, 600, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            errs.filterNotNull()
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) null else throw t
        }
    }

    private fun assertRendersClean(msg: String, code: String, entry: String = "box/0") {
        val errs = renderErrors(code, entry) ?: return // Skiko unavailable → skip
        assertEquals(emptyList(), errs, msg)
    }

    @Test
    fun labeledThisResolvesOuterSharedTransitionScope() {
        // `this@SharedTransitionLayout` (outer) must NOT resolve to the innermost AnimatedVisibility scope.
        // rememberSharedContentState + sharedBounds must dispatch on the real SharedTransitionScope.
        assertRendersClean(
            "shared-element modifier chain must render clean once labeled-this resolves the outer scope",
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.AnimatedVisibility
            import androidx.compose.animation.AnimatedVisibilityScope
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.animation.SharedTransitionScope
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            data class Key(val id: Long, val type: String)

            @Composable
            fun Content(sts: SharedTransitionScope, avs: AnimatedVisibilityScope) {
                with(sts) {
                    Text(
                        "hi",
                        modifier = Modifier.sharedBounds(
                            rememberSharedContentState(key = Key(1, "img")),
                            animatedVisibilityScope = avs,
                        ),
                    )
                }
            }

            @Composable
            fun box() {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        Content(this@SharedTransitionLayout, this)
                    }
                }
            }
            """,
        )
    }

    @Test
    fun nestedWithScopesDispatchRenderInOverlay() {
        // The DestinationBar shape: nested `with(sts) { with(avs) { Modifier.renderInSharedTransitionScopeOverlay() } }`.
        // The overlay modifier is a SharedTransitionScope member; the inner scope is AnimatedVisibilityScope.
        assertRendersClean(
            "renderInSharedTransitionScopeOverlay (outer scope member) must dispatch under a nested inner with-scope",
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.AnimatedVisibility
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable
            fun box() {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        with(this@SharedTransitionLayout) {
                            with(this@AnimatedVisibility) {
                                Text("hi", modifier = Modifier.renderInSharedTransitionScopeOverlay())
                            }
                        }
                    }
                }
            }
            """,
        )
    }

    @Test
    fun jetsnackPreviewWrapperCompositionLocalPattern() {
        // Faithful JetsnackPreviewWrapper: scopes provided via CompositionLocalProvider, read via `.current`,
        // then used in a shared-element modifier — the exact SnackCard/DestinationBar preview shape.
        assertRendersClean(
            "the JetsnackPreviewWrapper CompositionLocal scope pattern must render a shared-element modifier clean",
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.AnimatedVisibility
            import androidx.compose.animation.AnimatedVisibilityScope
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.animation.SharedTransitionScope
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.CompositionLocalProvider
            import androidx.compose.runtime.compositionLocalOf
            import androidx.compose.ui.Modifier

            data class Key(val id: Long)
            val LocalSts = compositionLocalOf<SharedTransitionScope?> { null }
            val LocalAvs = compositionLocalOf<AnimatedVisibilityScope?> { null }

            @Composable
            fun Item() {
                val sts = LocalSts.current ?: throw IllegalStateException("No shared element scope")
                val avs = LocalAvs.current ?: throw IllegalStateException("No nav scope")
                with(sts) {
                    Text(
                        "hi",
                        modifier = Modifier.sharedBounds(
                            rememberSharedContentState(key = Key(1)),
                            animatedVisibilityScope = avs,
                        ),
                    )
                }
            }

            @Composable
            fun box() {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        CompositionLocalProvider(
                            LocalSts provides this@SharedTransitionLayout,
                            LocalAvs provides this,
                        ) {
                            Item()
                        }
                    }
                }
            }
            """,
        )
    }

    @Test
    fun unprovidedSharedTransitionScopeLocalSurfacesAuthorThrow() {
        // Feed's HomePreview shape: `JetsnackTheme { Feed(...) }` (bare, NOT JetsnackPreviewWrapper). Feed's
        // SharedTransitionLayout does NOT provide LocalSharedTransitionScope, but DestinationBar / the snack items
        // read `LocalSharedTransitionScope.current ?: throw`. That throw is the AUTHOR's, from a missing provider —
        // the interpreter must surface it faithfully (a partial render), NOT swallow or misresolve it. The fix is
        // to PROVIDE the local (JetsnackPreviewWrapper), which the sibling test already renders clean.
        val errs = renderErrors(
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.animation.SharedTransitionScope
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.compositionLocalOf
            val LocalSts = compositionLocalOf<SharedTransitionScope?> { null }
            @Composable
            fun Child() {
                LocalSts.current ?: throw IllegalStateException("No shared element scope")
                Text("hi")
            }
            @Composable
            fun box() {
                SharedTransitionLayout { Child() }
            }
            """,
            "box/0",
        ) ?: return
        assertEquals(listOf("No shared element scope"), errs, "an unprovided compositionLocalOf must surface the author's throw")
    }

    @Test
    fun animateDpLambdaReturnBoxesValueClass() {
        // HighlightSnackItem shape: `transition.animateDp(label) { 20.dp }` — a lambda RETURNING a value-class Dp
        // fed to animateDp, whose converter casts the result to Dp. The interpreter holds Dp unboxed (Float), so
        // the lambda's return must be boxed at the proxy boundary; otherwise `Float cannot be cast to Dp`.
        assertRendersClean(
            "a lambda returning a value-class Dp (animateDp) must box its return so the library converter can cast it",
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.AnimatedVisibility
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.animation.core.animateDp
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.size
            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.getValue
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.draw.clip
            import androidx.compose.ui.unit.dp

            @Composable
            fun box() {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        val corner by transition.animateDp(label = "c") { 20.dp }
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(corner))) { Text("x") }
                    }
                }
            }
            """,
        )
    }

    @Test
    fun sharedBoundsWithSamConstructorBoundsTransform() {
        // SnackCard shape: a top-level `val` BoundsTransform (a fun-interface SAM constructor) passed as
        // `boundsTransform` to sharedBounds. Exercises SAM construction + top-level-val caching + passing the
        // proxy into the shared-element modifier, all in one render.
        assertRendersClean(
            "a SAM-constructor BoundsTransform used as sharedBounds(boundsTransform = …) must render clean",
            """
            @file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
            package demo
            import androidx.compose.animation.AnimatedVisibility
            import androidx.compose.animation.AnimatedVisibilityScope
            import androidx.compose.animation.BoundsTransform
            import androidx.compose.animation.SharedTransitionLayout
            import androidx.compose.animation.SharedTransitionScope
            import androidx.compose.animation.core.spring
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.geometry.Rect

            data class Key(val id: Long)
            val boundsTransform = BoundsTransform { _, _ -> spring<Rect>() }

            @Composable
            fun Content(sts: SharedTransitionScope, avs: AnimatedVisibilityScope) {
                with(sts) {
                    Text(
                        "hi",
                        modifier = Modifier.sharedBounds(
                            rememberSharedContentState(key = Key(1)),
                            animatedVisibilityScope = avs,
                            boundsTransform = boundsTransform,
                        ),
                    )
                }
            }

            @Composable
            fun box() {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        Content(this@SharedTransitionLayout, this)
                    }
                }
            }
            """,
        )
    }

    private class MemDir(private val kids: List<VirtualFile>) : VirtualFile {
        override val path = "src"; override val name = "src"; override val isDirectory = true
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = kids
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
    private class MemFile(override val name: String, private val content: String) : VirtualFile {
        override val path = name; override val isDirectory = false; override val exists = true
        override val length get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(content.hashCode().toString())
        override fun readBytes() = content.toByteArray()
        override fun readText(): CharSequence = content
    }
    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = MemFile("Main.kt", text.toString()); override val version = 1L
        override fun length() = text.length
    }
}
