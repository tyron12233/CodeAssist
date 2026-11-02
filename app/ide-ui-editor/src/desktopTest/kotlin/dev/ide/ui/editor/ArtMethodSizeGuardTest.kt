package dev.ide.ui.editor

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the editor's biggest composables under ART's huge-method threshold.
 *
 * ART will not compile a method longer than 10,000 dex code units, JIT or AOT, so it runs interpreted on every
 * call. For `CodeEditorContent` that is every recomposition of the editor, and it has crossed the line twice:
 * 16,807 before the July split, then back up to 13,537 as new features (hover, context menu, seasonal effects,
 * in-pane overlays) were added inline. Nothing on the JVM notices; only a dexdump of the APK did. This test dexes
 * the compiled classes with the Android SDK's d8 and reads each method's size with dexdump, so the next feature
 * that pushes a method over fails here instead of silently slowing every phone.
 *
 * The desktop-compiled class dexes to the same size as the Android one (both 8,022 units for CodeEditorContent
 * when this was written). Skips, with a note, when no Android SDK with build-tools is found.
 */
class ArtMethodSizeGuardTest {

    @Test
    fun editorComposablesStayCompilableOnArt() {
        val tools = buildTools() ?: run {
            println("ArtMethodSizeGuardTest: no Android SDK build-tools found; skipped")
            return
        }
        val work = Files.createTempDirectory("art-method-size").toFile()
        try {
            val classes = GUARDED.map { name ->
                val bytes = javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.readBytes()
                    ?: fail("compiled class $name not on the test classpath")
                File(work, name.substringAfterLast('.') + ".class").apply { writeBytes(bytes) }
            }
            val out = File(work, "out").apply { mkdirs() }
            val java = File(System.getProperty("java.home"), "bin/java").path
            run(listOf(java, "-cp", tools.d8Jar.path, "com.android.tools.r8.D8", "--min-api", "26", "--release",
                "--output", out.path) + classes.map { it.path })
            val dump = run(listOf(tools.dexdump.path, "-d", File(out, "classes.dex").path))

            val sizes = methodSizes(dump)
            assertTrue(sizes.containsKey("CodeEditorContent"), "dexdump did not list CodeEditorContent: ${sizes.keys.take(20)}")
            val over = sizes.filterValues { it > ART_HUGE_METHOD_UNITS }
            println("ArtMethodSizeGuardTest: largest " +
                sizes.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}=${it.value}" })
            assertTrue(
                over.isEmpty(),
                "over ART's $ART_HUGE_METHOD_UNITS-unit compile limit, so interpreted on device: $over. " +
                    "Move an emission block into its own @Composable (see EditorCanvasLayer / EditorDialogsLayer).",
            )
        } finally {
            work.deleteRecursively()
        }
    }

    /** Method name -> `insns size` (16-bit code units), the largest per name across the dumped classes. */
    private fun methodSizes(dump: String): Map<String, Int> {
        val sizes = HashMap<String, Int>()
        var name: String? = null
        for (line in dump.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("name") && t.contains(":") && t.contains('\'')) {
                name = t.substringAfter('\'').substringBefore('\'')
            } else if (t.startsWith("insns size") && name != null) {
                val units = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: continue
                sizes.merge(name, units, ::maxOf)
            }
        }
        return sizes
    }

    private fun run(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "timed out: ${cmd.first()}" }
        check(p.exitValue() == 0) { "${cmd.first()} failed (${p.exitValue()}):\n${text.take(2000)}" }
        return text
    }

    private class Tools(val d8Jar: File, val dexdump: File)

    /** The newest build-tools under the SDK named by ANDROID_HOME / ANDROID_SDK_ROOT / local.properties. */
    private fun buildTools(): Tools? {
        val sdk = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), localPropertiesSdk())
            .filterNotNull().map(::File).firstOrNull { File(it, "build-tools").isDirectory } ?: return null
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "dexdump.exe" else "dexdump"
        return File(sdk, "build-tools").listFiles().orEmpty()
            .filter { File(it, "lib/d8.jar").isFile && File(it, exe).canExecute() }
            .maxByOrNull { dir -> dir.name.split('.', '-').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, v -> acc * 1000 + v } }
            ?.let { Tools(File(it, "lib/d8.jar"), File(it, exe)) }
    }

    private fun localPropertiesSdk(): String? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "local.properties")
            if (f.isFile) return Properties().apply { f.inputStream().use(::load) }.getProperty("sdk.dir")
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        /** ART's default `--huge-method-max`: a longer method is not compiled. */
        const val ART_HUGE_METHOD_UNITS = 10_000

        /** The editor's file classes that hold its largest composables. */
        val GUARDED = listOf(
            "dev.ide.ui.editor.CodeEditorKt",
            "dev.ide.ui.editor.EditorRenderingKt",
            "dev.ide.ui.editor.EditorOverlayLayersKt",
        )
    }
}
