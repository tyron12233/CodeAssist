package dev.ide.lang.kotlin

import dev.ide.lang.CompilationContext
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.complete
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.testkit.DiskVirtualFile
import dev.ide.testkit.TestDocument
import dev.ide.testkit.TestJars
import dev.ide.testkit.caret
import dev.ide.testkit.compilationContext
import dev.ide.testkit.writeSource
import java.nio.file.Files
import java.nio.file.Path

/** A [dev.ide.vfs.VirtualFile] backed by a real filesystem path — for source-root walking + classpath reads. */
typealias DiskFile = DiskVirtualFile

/** A [dev.ide.lang.incremental.DocumentSnapshot] over an in-memory snippet. */
typealias SnippetDoc = TestDocument

/** The kotlin-stdlib jar on the test classpath (the one carrying `kotlin/Pair.class`). */
fun stdlibJarPath(): Path = TestJars.kotlinStdlib()

/** A minimal [CompilationContext]: a source dir + library jars (stdlib by default). */
fun fakeContext(srcDir: Path, libJars: List<Path> = listOf(stdlibJarPath())): CompilationContext =
    compilationContext(sourceRoots = listOf(srcDir), libraries = libJars)

/** Write [files] (name -> content) into a fresh temp source dir and return it. */
fun tempProject(files: Map<String, String>): Path {
    val dir = Files.createTempDirectory("lang-kotlin-test")
    files.forEach { (name, content) -> dir.writeSource(name, content, trim = false) }
    return dir
}

/** Run completion on [code] with the caret at the FIRST occurrence of the `|` marker (which is stripped). */
suspend fun KotlinSourceAnalyzer.completeAtCaret(srcDir: Path, fileName: String, code: String): CompletionResult {
    val (clean, offset) = caret(code)
    val doc = SnippetDoc(clean, DiskFile(srcDir.resolve(fileName)))
    return complete(CompletionRequest(doc, offset, CompletionTrigger.TypedChar('.')))
}
