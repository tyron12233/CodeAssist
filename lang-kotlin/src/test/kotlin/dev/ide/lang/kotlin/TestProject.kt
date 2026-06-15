package dev.ide.lang.kotlin

import dev.ide.lang.CompilationContext
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** A [VirtualFile] backed by a real filesystem path — for source-root walking + classpath reads in tests. */
class DiskFile(val p: Path) : VirtualFile {
    override val path: String get() = p.toString()
    override val name: String get() = p.fileName.toString()
    override val isDirectory: Boolean get() = Files.isDirectory(p)
    override val exists: Boolean get() = Files.exists(p)
    override val length: Long get() = if (exists && !isDirectory) Files.size(p) else 0
    override fun parent(): VirtualFile? = p.parent?.let { DiskFile(it) }
    override fun children(): List<VirtualFile> =
        if (isDirectory) Files.list(p).use { s -> s.toList() }.map { DiskFile(it) } else emptyList()
    override fun contentHash(): ContentHash = ContentHash("")
    override fun readBytes(): ByteArray = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
    override fun readText(): CharSequence = if (exists && !isDirectory) Files.readString(p) else ""
}

/** The kotlin-stdlib jar on the test classpath (the one carrying `kotlin/Pair.class`). */
fun stdlibJarPath(): Path {
    val cp = System.getProperty("java.class.path").split(File.pathSeparator)
    val entry = cp.firstOrNull { e ->
        e.endsWith(".jar") && runCatching { ZipFile(e).use { it.getEntry("kotlin/Pair.class") != null } }.getOrDefault(false)
    } ?: error("kotlin-stdlib jar not found on test classpath")
    return Path.of(entry)
}

private fun snapshotOf(jars: List<Path>, kind: ClasspathEntryKind) = object : ClasspathSnapshot {
    override val entries: List<ClasspathEntry> = jars.map { ClasspathEntry(DiskFile(it), kind) }
    override fun fingerprint(): ContentHash = ContentHash("")
}

/** A minimal [CompilationContext]: a source dir + library jars (stdlib). */
fun fakeContext(srcDir: Path, libJars: List<Path> = listOf(stdlibJarPath())): CompilationContext =
    object : CompilationContext {
        override val sourceRoots: List<VirtualFile> = listOf(DiskFile(srcDir))
        override val classpath: ClasspathSnapshot = snapshotOf(libJars, ClasspathEntryKind.LIBRARY)
        override val bootClasspath: ClasspathSnapshot = snapshotOf(emptyList(), ClasspathEntryKind.SDK_BOOTCLASSPATH)
        override val languageLevel: LanguageLevel = LanguageLevel.JAVA_17
        override val outputDir: VirtualFile = DiskFile(srcDir)
        override val processors = emptyList<dev.ide.lang.AnnotationProcessor>()
    }

/** Write [files] (name -> content) into a fresh temp source dir and return it. */
fun tempProject(files: Map<String, String>): Path {
    val dir = Files.createTempDirectory("lang-kotlin-test")
    files.forEach { (name, content) ->
        val f = dir.resolve(name)
        Files.createDirectories(f.parent ?: dir)
        Files.writeString(f, content)
    }
    return dir
}

class SnippetDoc(
    override val text: CharSequence,
    override val file: VirtualFile,
    override val version: Long = 1,
) : DocumentSnapshot {
    override fun length(): Int = text.length
}

/** Run completion on [code] with the caret at the FIRST occurrence of the `|` marker (which is stripped). */
suspend fun KotlinSourceAnalyzer.completeAtCaret(srcDir: Path, fileName: String, code: String): CompletionResult {
    val caret = code.indexOf('|')
    require(caret >= 0) { "no caret marker '|' in code" }
    val clean = code.removeRange(caret, caret + 1)
    val doc = SnippetDoc(clean, DiskFile(srcDir.resolve(fileName)))
    return completion!!.complete(CompletionRequest(doc, caret, CompletionTrigger.TypedChar('.')))
}
