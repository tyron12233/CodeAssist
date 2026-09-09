package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.LazyPreviewDeclProvider
import dev.ide.lang.kotlin.interp.PreviewLazyFile
import dev.ide.lang.kotlin.interp.PreviewLoweringDiskCache
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Profiles the Compose preview pipeline stage by stage against a REAL large project (a local Jetsnack
 * checkout): entry-file lowering, cross-file expansion (per pulled file), warm re-lowering (the keystroke
 * path), the first/second interpreted render, and the disk-cache restart path. Skips silently when no
 * checkout is present, so it only runs where `JETSNACK_SRC` (or the default path) exists. Timings print to
 * stdout and to `/tmp/jetsnack-profile.txt`.
 */
class JetsnackPreviewProfileTest {

    private val report = StringBuilder()

    private fun say(line: String) {
        println(line)
        report.appendLine(line)
    }


    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        say("  %-52s %8.1f ms".format(label, (System.nanoTime() - t0) / 1e6))
        return r
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun profileJetsnackPreviews() {
        val root = Paths.get(System.getenv("JETSNACK_SRC")
            ?: "/Users/tyronscott/JavaProjects/compose-samples/Jetsnack/app/src/main/java")
        if (!Files.isDirectory(root)) return

        val service = timed("KotlinSymbolService init") {
            previewSymbolService(listOf(DiskVF(root)))
        }
        val lowering = KotlinPreviewLowering(service)

        // Warm the classpath model on a trivial snippet so the first entry's numbers aren't dominated by the
        // one-time jar scan (the device keeps a persistent index for that part).
        say("— classpath warm-up —")
        timed("warm-up (lower `Text(\"hi\")` snippet)") {
            val code = "package warm\nimport androidx.compose.material3.Text\n" +
                "import androidx.compose.runtime.Composable\n@Composable fun warm() { Text(\"hi\") }\n"
            lowering.program(parse(MemVF("/warm/Warm.kt", code), code, 0))
        }

        profileEntry(root, lowering, "com/example/jetsnack/ui/components/Snacks.kt", "SnackCardPreview/0")
        profileEntry(root, lowering, "com/example/jetsnack/ui/snackdetail/SnackDetail.kt", "SnackDetailPreview/0")
        profileEntry(root, lowering, "com/example/jetsnack/ui/home/Feed.kt", "HomePreview/0")
        profileEntry(root, lowering, "com/example/jetsnack/ui/home/cart/Cart.kt", "CartPreview/0")

        profileRestart(root, service)

        runCatching { Files.writeString(Paths.get("/tmp/jetsnack-profile.txt"), report.toString()) }
    }

    /** The project-reopen path: a fresh lowering instance whose only head start is the disk cache. */
    private fun profileRestart(root: Path, service: KotlinSymbolService) {
        val cacheDir = Files.createTempDirectory("jetsnack-plc")
        val sd = root.resolve("com/example/jetsnack/ui/snackdetail/SnackDetail.kt")
        val parsed = parse(DiskVF(sd), Files.readString(sd), 0)
        say("")
        say("=== disk cache: simulated restart (SnackDetail) ===")
        val cache1 = PreviewLoweringDiskCache(cacheDir, "bench")
        val fresh = KotlinPreviewLowering(service, diskCache = cache1)
        timed("pass 1: fresh instance, cold lower + persist") { fresh.crossFileModel(parsed) }
        cache1.flush()
        say("    cache files: ${Files.list(cacheDir).use { s -> s.count() }}")
        val restarted = KotlinPreviewLowering(service, diskCache = PreviewLoweringDiskCache(cacheDir, "bench"))
        timed("pass 2: RESTARTED instance, decode from disk") { restarted.crossFileModel(parsed) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun profileEntry(root: Path, lowering: KotlinPreviewLowering, rel: String, entryKey: String) {
        val p = root.resolve(rel)
        val text = Files.readString(p)
        say("")
        say("=== $rel → $entryKey ===")
        val parsed = parse(DiskVF(p), text, 0)

        // Cross-file expansion with a timing provider: locate time per name plus each pulled declaration's
        // lower time, attributed to its file.
        val perFile = LinkedHashMap<String, Double>()
        val inner = lowering.lazyDeclProvider()
        fun timedFile(f: PreviewLazyFile): PreviewLazyFile = object : PreviewLazyFile {
            override val path: String get() = f.path
            override fun functionKeys(name: String) = f.functionKeys(name)
            override fun anonymousClassesFor(key: String) = f.anonymousClassesFor(key)
            override fun function(key: String): ResolvedFunction? {
                val t0 = System.nanoTime()
                val r = f.function(key)
                perFile.merge(f.path, (System.nanoTime() - t0) / 1e6, Double::plus)
                return r
            }
            override fun classesFor(nameOrFqn: String): List<ResolvedClass> {
                val t0 = System.nanoTime()
                val r = f.classesFor(nameOrFqn)
                perFile.merge(f.path, (System.nanoTime() - t0) / 1e6, Double::plus)
                return r
            }
        }
        val provider = object : LazyPreviewDeclProvider {
            override fun fileDeclaringType(fqn: String): PreviewLazyFile? =
                inner.fileDeclaringType(fqn)?.let(::timedFile)
            override fun filesDeclaringFunction(name: String): List<PreviewLazyFile> =
                inner.filesDeclaringFunction(name).map(::timedFile)
        }

        val sink = dev.ide.platform.log.LogSink { r ->
            if (r.tag == "kotlin-perf" && "total=" in r.message) say("    [perf] ${r.message}")
        }
        dev.ide.platform.log.Log.addSink(sink)
        dev.ide.platform.log.PerfTrace.enabled = true
        val (seed, model) = dev.ide.lang.kotlin.KotlinPerf.trace("bench.cold") {
            val s = timed("seed: lower entry file") { lowering.loweredEntryFile(parsed) }
            s to timed("expand: cross-file closure") { lowering.expand(s, provider) }
        }
        dev.ide.platform.log.PerfTrace.enabled = false
        dev.ide.platform.log.Log.removeSink(sink)
        say("    program=${model.program.size} fns (${seed.program.size} entry-file), classes=${model.classes.size}, pulled files=${perFile.size}")
        model.program[entryKey]?.let { e ->
            val rf = dev.ide.lang.kotlin.interp.reachableSourceFunctions(e, model.program, model.classes)
            val rc = dev.ide.lang.kotlin.interp.reachableSourceClasses(e, model.program, model.classes)
            say("    reachable from $entryKey: ${rf.size}/${model.program.size} fns, ${rc.size}/${model.classes.size} classes")
        }
        perFile.entries.sortedByDescending { it.value }.take(10).forEach { (k, v) ->
            say("    %8.1f ms  %s".format(v, k.substringAfterLast('/')))
        }

        timed("warm: crossFileModel (same text)") { lowering.crossFileModel(parsed) }

        // The keystroke path: one char typed inside a function body → same signatures, one function re-lowers.
        val editAt = text.lastIndexOf("}")
        val edited = text.substring(0, editAt) + " " + text.substring(editAt)
        val reparsed = parse(DiskVF(p), edited, 1)
        timed("keystroke: re-lower + expand (body edit)") { lowering.crossFileModel(reparsed) }

        val entryFn = model.program[entryKey]
        if (entryFn == null) {
            say("    !! entry $entryKey not lowered; have ${model.program.keys.filter { it.contains("Preview") }}")
            return
        }
        if (!entryFn.isComplete) say("    (entry has ${entryFn.diagnostics.size} lowering gaps — renders partially)")

        val errs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val renderer = ComposePreviewRenderer(loader = null)
        val content: @Composable () -> Unit = {
            renderer.Render(entryFn, model.program, model.classes, emptyList(),
                onError = { errs.add("top: ${it.message}") },
                onPartialError = { it?.let { t -> errs.add("partial: ${t.message}") } })
        }
        try {
            val scene = timed("render: scene construct") { ImageComposeScene(400, 800, Density(1f), content = content) }
            try {
                timed("render: first frame") { scene.render(0L) }
                timed("render: second frame") { scene.render(16_000_000L) }
            } finally { scene.close() }
        } catch (t: Throwable) {
            say("    render unavailable here: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        errs.distinct().take(6).forEach { say("    err: ${it.take(160)}") }
    }

    private fun parse(vf: VirtualFile, text: String, version: Long): KotlinParsedFile =
        KotlinIncrementalParser().parseFull(Doc(vf, text, version)) as KotlinParsedFile

    private class Doc(override val file: VirtualFile, override val text: CharSequence, override val version: Long) : DocumentSnapshot {
        override fun length() = text.length
    }

    private class DiskVF(val p: Path) : VirtualFile {
        override val path: String get() = p.toString()
        override val name: String get() = p.fileName?.toString() ?: p.toString()
        override val isDirectory: Boolean get() = Files.isDirectory(p)
        override val exists: Boolean get() = Files.exists(p)
        override val length: Long get() = if (exists && !isDirectory) Files.size(p) else 0
        override fun parent(): VirtualFile? = p.parent?.let { DiskVF(it) }
        override fun children(): List<VirtualFile> =
            if (isDirectory) Files.list(p).use { s -> s.toList() }.map { DiskVF(it) } else emptyList()
        override fun contentHash(): ContentHash = ContentHash("")
        override fun readBytes(): ByteArray = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
        override fun readText(): CharSequence = if (exists && !isDirectory) Files.readString(p) else ""
    }

    private class MemVF(override val path: String, private val content: String) : VirtualFile {
        override val name: String get() = path.substringAfterLast('/')
        override val isDirectory: Boolean get() = false
        override val exists: Boolean get() = true
        override val length: Long get() = content.length.toLong()
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash(content.hashCode().toString())
        override fun readBytes(): ByteArray = content.toByteArray()
        override fun readText(): CharSequence = content
    }
}
