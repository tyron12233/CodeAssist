package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.walk
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
import kotlin.test.assertNotNull

/**
 * `Animatable(0.4f)` must lower to the `Animatable(Float, Float = …)` FACTORY (`AnimatableKt`, 2 params, one
 * defaulted), NOT the exact-arity `Animatable(Color)` overload (`SingleValueAnimationKt`). The lowering's
 * arity filter previously took the single exact-arity candidate — `Animatable(Color)` — and invoking it with a
 * Float threw `argument type mismatch`, which blanked the composable using it (a `remember { Animatable(0.4f) }`
 * pop-in scale → `Modifier.scale(a.value)` collapsed the content). Regression guard for defaulted-overload
 * selection when the only exact-arity candidate can't accept the argument.
 */
class AnimatableOverloadTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }.map { Paths.get(it) }

    private fun animatableCallee(code: String): ResolvedCallable.Library? {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(ADoc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        var found: ResolvedCallable.Library? = null
        program.getValue("P/0").body.walk {
            if (it is RNode.Call && it.callee.displayName == "Animatable") found = it.callee as? ResolvedCallable.Library
        }
        return found
    }

    private val HEADER = """
        package demo
        import androidx.compose.animation.core.Animatable
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.remember
    """.trimIndent()

    @Test fun floatAnimatableResolvesToTheFloatFactoryNotColor() {
        val callee = assertNotNull(
            animatableCallee("$HEADER\n@Composable fun P() { val a = Animatable(0.4f); a.value }"),
            "Animatable(0.4f) should resolve to an Animatable factory",
        )
        assertEquals(
            "androidx.compose.animation.core.AnimatableKt", callee.ownerFqn,
            "Animatable(0.4f) must pick the Float factory (AnimatableKt), not the Color overload; params=${callee.paramTypes}",
        )
    }

    @Test fun floatAnimatableInsideRememberResolvesToTheFloatFactory() {
        val callee = assertNotNull(
            animatableCallee("$HEADER\n@Composable fun P() { val a = remember { Animatable(0.4f) }; a.value }"),
            "remembered Animatable(0.4f) should resolve",
        )
        assertEquals(
            "androidx.compose.animation.core.AnimatableKt", callee.ownerFqn,
            "remember { Animatable(0.4f) } must pick the Float factory; params=${callee.paramTypes}",
        )
    }

    private class ADoc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = AF(); override val version = 1L
        override fun length() = text.length
    }
    private class AF : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
