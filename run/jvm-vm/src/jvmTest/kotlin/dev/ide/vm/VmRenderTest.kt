package dev.ide.vm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose UI to pixels through the VM, against the same frame rendered for real: the interpreted
 * composition, layout, text and drawing call the same skia, so the two frames must be identical.
 */
class VmRenderTest {
    private val owner = "dev/ide/vm/fixtures/Render"

    private fun vm(): Vm = Vm(
        ClassPath(TestClasspath.entries),
        bindings = ReflectiveNativeBindings(
            loader = javaClass.classLoader,
            prepare = {
                val library = Class.forName("org.jetbrains.skiko.Library")
                library.getMethod("load").invoke(library.getField("INSTANCE").get(null))
            },
        ),
        systemProperties = listOf("os.name", "os.arch", "java.vendor", "java.version").associateWith { System.getProperty(it) },
    )

    @Test
    fun aMaterialScreenRendersTheSamePixelsInterpreted() {
        val real = dev.ide.vm.fixtures.Render.sampleSummary()
        val vm = vm()
        val start = System.nanoTime()
        val interpreted = vm.invokeStatic(owner, "sampleSummary", "()Ljava/lang/String;")
        val ms = (System.nanoTime() - start) / 1_000_000
        println("VM-RENDER real=$real vm=$interpreted in ${ms}ms, ${vm.steps} steps, ${vm.loadedClassCount} classes")
        val png = vm.invokeStatic(owner, "samplePng", "()[B") as ByteArray
        val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
        File(out, "jvm-vm-render.png").writeBytes(png)
        assertEquals(real, interpreted)
    }
}
