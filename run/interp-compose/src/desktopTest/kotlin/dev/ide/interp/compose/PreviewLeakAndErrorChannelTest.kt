package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.interp.InterpretedLambda
import dev.ide.interp.LambdaProxyHandler
import dev.ide.interp.lambdaProxyEquals
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two things a preview must not do quietly: fail to unregister a listener it registered (a lambda proxy that is
 * not equal to itself is never found by a list-backed registry's `remove`), and swallow an effect that died.
 */
class PreviewLeakAndErrorChannelTest {

    private class Outcome(val hardError: String?, val partials: List<String>)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(code: String, entry: String, settleMillis: Long = 0): Outcome? {
        val parsed = KotlinIncrementalParser().parseFull(Doc(code.trimIndent())) as KotlinParsedFile
        val lowering = KotlinPreviewLowering(previewSymbolService())
        val program = lowering.program(parsed)
        val classes = lowering.classes(parsed)
        val entryFn = program[entry] ?: error("no entry `$entry`; have ${program.keys}")
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        var hardError: String? = null
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entryFn, program, classes, emptyList(), onError = { hardError = it.message }, onPartialError = { partials.add(it?.message) })
        }
        return try {
            val scene = ImageComposeScene(300, 600, Density(1f), content = content)
            try {
                scene.render(0L)
                // Let a bridge-thread effect run, then give the drain a frame to report what it recorded.
                val deadline = System.currentTimeMillis() + settleMillis
                var frame = 1L
                while (System.currentTimeMillis() < deadline && partials.all { it == null }) {
                    Thread.sleep(20)
                    scene.render(frame++ * 16_000_000L)
                }
            } finally { scene.close() }
            Outcome(hardError, partials.filterNotNull())
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return null
            throw t
        }
    }

    private fun proxyOf(lambda: InterpretedLambda): Runnable =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(Runnable::class.java),
            LambdaProxyHandler(lambda) { proxy, method, args ->
                when (method.name) {
                    "equals" -> lambdaProxyEquals(proxy, lambda, args?.getOrNull(0))
                    "hashCode" -> System.identityHashCode(lambda)
                    else -> null
                }
            },
        ) as Runnable

    @Test
    fun aListenerRegisteredInAListCanBeRemovedAgain() {
        val lambda = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = null
        }
        // `addListener(l)` then `removeListener(l)` wraps the SAME interpreted lambda in two proxies, and
        // ArrayList.remove finds the first only through `equals` (it has no identity shortcut).
        val listeners = ArrayList<Runnable>()
        val added = proxyOf(lambda)
        listeners.add(added)
        assertTrue(listeners.contains(added), "a proxy equals itself")
        assertTrue(listeners.remove(proxyOf(lambda)), "a second proxy of the same lambda equals the first")
        assertTrue(listeners.isEmpty())
        val other = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = null
        }
        assertTrue(proxyOf(other) != added, "a proxy of a different lambda is a different listener")
    }

    @Test
    fun anEffectThatDiesIsReportedAsAPartialError() {
        val o = render(
            """
            package demo
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun box() {
                LaunchedEffect(Unit) { throw IllegalStateException("effect boom") }
                Text("still here")
            }
            """,
            "box/0",
            settleMillis = 5000,
        ) ?: return
        assertNull(o.hardError, "a failed effect must not fail the whole preview")
        assertTrue(o.partials.any { it == "effect boom" }, "the effect's failure reaches the host between passes; was ${o.partials}")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F(); override val version = 1L
        override fun length() = text.length
    }
    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash(""); override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
