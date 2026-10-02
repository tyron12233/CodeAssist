package dev.ide.vm

import dev.ide.platform.createDirectories
import dev.ide.platform.writeFileAtomically
import dev.ide.vm.skiko.SkikoNativeBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The same Material screen the JVM test renders, here interpreted on iOS and drawn by the iOS build of
 * skia. Fonts and anti-aliasing come from a different skia build, so the pixels are not held to the JVM's
 * hash; the frame must have the JVM's size and close to its amount of ink, and is written out to look at.
 */
class VmRenderIosTest {
    @Test
    fun aMaterialScreenRendersOnIos() {
        val vm = Vm(
            ClassPath(TestClasspath.entries),
            bindings = SkikoNativeBindings(),
            systemProperties = mapOf("os.name" to "Mac OS X", "os.arch" to "aarch64"),
        )
        val owner = "dev/ide/vm/fixtures/Render"
        val start = TimeSource.Monotonic.markNow()
        val summary = vm.invokeStatic(owner, "sampleSummary", "()Ljava/lang/String;") as String
        val coldMs = start.elapsedNow().inWholeMilliseconds
        val warmStart = TimeSource.Monotonic.markNow()
        val again = vm.invokeStatic(owner, "sampleSummary", "()Ljava/lang/String;") as String
        val warmMs = warmStart.elapsedNow().inWholeMilliseconds
        println("VM-RENDER ios: $summary cold ${coldMs}ms, warm ${warmMs}ms, ${vm.steps} steps, ${vm.loadedClassCount} classes")
        val png = vm.invokeStatic(owner, "samplePng", "()[B") as ByteArray
        createDirectories(TestClasspath.outputDir)
        writeFileAtomically("${TestClasspath.outputDir}/ios-render.png", png)
        assertEquals(summary, again)
        assertTrue(summary.startsWith("360x220 "), summary)
        val inked = summary.substringAfter("inked=").substringBefore(' ').toInt()
        assertTrue(inked in 12_000..20_000, "inked $inked is far from the JVM's 15985")
    }
}
