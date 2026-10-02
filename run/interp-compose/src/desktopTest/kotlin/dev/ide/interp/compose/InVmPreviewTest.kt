package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import dev.ide.vm.ClassPath
import dev.ide.vm.ReflectiveNativeBindings
import dev.ide.vm.Vm
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The preview pipeline run INSIDE `:jvm-vm`: a source `@Preview` lowered here, its tree handed over as a
 * blob, and then the tree interpreter, the Compose bridge and Compose itself all interpreted by the VM, with
 * only skia native. That is how iOS previews. The frame must be the one this JVM renders directly.
 */
class InVmPreviewTest {

    private val source = """
        package demo
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Surface
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp
        @Composable fun Greeting(name: String) {
            Text("Hello, " + name + "!")
        }
        @Composable fun P() {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Greeting("iOS")
                        Button(onClick = {}) { Text("Press me") }
                    }
                }
            }
        }
    """.trimIndent()

    private fun blob(): ByteArray {
        val parsed = KotlinIncrementalParser().parseFull(Doc(source)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = program["P/0"] ?: error("no P/0; have ${program.keys}")
        return VmPreviewEntry.encode(entry, program, emptyList())
    }

    @Test
    fun aSourcePreviewRendersTheSamePixelsInsideTheVm() {
        val blob = blob()
        val direct = VmPreviewEntry.renderSignature(blob, 360, 220, 2f)
        println("IN-VM-PREVIEW direct summary ${VmPreviewEntry.renderSummary(blob, 360, 220, 2f)}")

        val vm = Vm(
            // This JVM's own class path, less kotlin-reflect: the preview pipeline does not use it, and a
            // project that does not depend on it would not have it either.
            ClassPath(System.getProperty("java.class.path").split(File.pathSeparator).filterNot { "kotlin-reflect" in File(it).name }),
            bindings = ReflectiveNativeBindings(
                loader = javaClass.classLoader,
                prepare = {
                    val library = Class.forName("org.jetbrains.skiko.Library")
                    library.getMethod("load").invoke(library.getField("INSTANCE").get(null))
                },
            ),
            systemProperties = listOf("os.name", "os.arch", "java.vendor", "java.version").associateWith { System.getProperty(it) },
        )
        val owner = "dev/ide/interp/compose/VmPreviewEntry"
        val start = System.nanoTime()
        val interpreted = vm.invokeStatic(owner, "renderSignature", "([BIIF)J", blob, 360, 220, 2f)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("IN-VM-PREVIEW direct=$direct vm=$interpreted in ${ms}ms, ${vm.steps} steps, ${vm.loadedClassCount} classes, blob ${blob.size} bytes")
        val png = vm.invokeStatic(owner, "renderPng", "([BIIF)[B", blob, 360, 220, 2f) as ByteArray
        File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }, "in-vm-preview.png").writeBytes(png)
        val ours = vm.loadedClassNames().filter { it.startsWith("dev/ide/") }
        println("IN-VM-PREVIEW our packages: " + ours.map { it.substringBeforeLast('/') }.groupingBy { it }.eachCount().toSortedMap())
        assertEquals(direct, interpreted)
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version = 1L
        override fun length() = text.length
    }

    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
