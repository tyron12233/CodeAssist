package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.complete
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class StubFile(override val path: String, private val content: String = "") : VirtualFile {
    override val name get() = path.substringAfterLast('/')
    override val isDirectory = false
    override val exists = true
    override val length get() = content.length.toLong()
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash() = ContentHash(content.hashCode().toString())
    override fun readBytes() = content.toByteArray()
    override fun readText(): CharSequence = content
}

private object EmptyClasspath : ClasspathSnapshot {
    override val entries: List<ClasspathEntry> = emptyList()
    override fun fingerprint() = ContentHash("")
}

private fun bootOf(vararg paths: String) = object : ClasspathSnapshot {
    override val entries = paths.map { ClasspathEntry(StubFile(it), ClasspathEntryKind.SDK_BOOTCLASSPATH) }
    override fun fingerprint() = ContentHash(paths.joinToString())
}

/** An analyzer whose sourcepath is [sourceDirs] and whose platform is the current JDK's jrt image. */
fun analyzer(sourceDirs: List<Path>): JdtSourceAnalyzer {
    val ctx = object : CompilationContext {
        override val sourceRoots: List<VirtualFile> = sourceDirs.map { StubFile(it.toString()) }
        override val classpath: ClasspathSnapshot = EmptyClasspath
        override val bootClasspath: ClasspathSnapshot = bootOf(System.getProperty("java.home"))
        override val languageLevel = LanguageLevel.JAVA_17
        override val outputDir: VirtualFile = StubFile("/out")
        override val processors: List<AnnotationProcessor> = emptyList()
    }
    return JdtSourceAnalyzer(ctx)
}

/** Writes [files] (relPath -> content) under a fresh temp dir and returns (analyzer, dir). */
fun workspaceWith(vararg files: Pair<String, String>): Pair<JdtSourceAnalyzer, Path> {
    val dir = Files.createTempDirectory("jdt-test")
    for ((rel, content) in files) {
        val f = dir.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }
    return analyzer(listOf(dir)) to dir
}

/**
 * Completes [codeWithCaret] (a `|CARET|` marks the caret) as if editing [file]; returns each item's bare
 * name. A method's insertText now carries `()`, so we take the part before `(` — the identifier these
 * member-presence / ranking tests care about (the `()` insertion itself is covered by CompletionInsertionTest).
 */
fun completeLabels(analyzer: JdtSourceAnalyzer, file: Path, codeWithCaret: String): List<String> {
    val offset = codeWithCaret.indexOf("|CARET|")
    require(offset >= 0) { "missing |CARET|" }
    val text = codeWithCaret.replace("|CARET|", "")
    val request = CompletionRequest(Snapshot(StubFile(file.toString(), text), 1, text), offset, CompletionTrigger.Explicit)
    return runSync { analyzer.complete(request) }.items.map { it.insertText.substringBefore('(') }
}

/** Like [completeLabels] but returns the full [CompletionResult] (to inspect kinds, detail, edits). */
fun completeResult(analyzer: JdtSourceAnalyzer, file: Path, codeWithCaret: String): CompletionResult {
    val offset = codeWithCaret.indexOf("|CARET|")
    require(offset >= 0) { "missing |CARET|" }
    val text = codeWithCaret.replace("|CARET|", "")
    val request = CompletionRequest(Snapshot(StubFile(file.toString(), text), 1, text), offset, CompletionTrigger.Explicit)
    return runSync { analyzer.complete(request) }
}

/** Signature help at the `|CARET|` in [codeWithCaret], as if editing [file]. */
fun signatureHelpAt(analyzer: JdtSourceAnalyzer, file: Path, codeWithCaret: String): dev.ide.lang.signature.SignatureHelp? {
    val offset = codeWithCaret.indexOf("|CARET|")
    require(offset >= 0) { "missing |CARET|" }
    val text = codeWithCaret.replace("|CARET|", "")
    val request = dev.ide.lang.signature.SignatureHelpRequest(
        Snapshot(StubFile(file.toString(), text), 1, text), offset, dev.ide.lang.signature.SignatureHelpTrigger.Explicit,
    )
    return runSync { analyzer.signatureHelp!!.signatureHelp(request) }
}

private class Snapshot(override val file: VirtualFile, override val version: Long, override val text: CharSequence) : DocumentSnapshot {
    override fun length() = text.length
}

fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
