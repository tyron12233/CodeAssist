package dev.ide.lang.kotlin.compile

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Kotlin compiler compiled into the host, for a host whose JVM only interprets.
 *
 * kotlinc is Java, and the JVM an iOS app embeds runs it a few dozen times slower than a compiling one: an
 * app's Kotlin took most of a minute. Such a host carries the compiler compiled ahead of time, as a library
 * with a runtime of its own, and sets [runner] to it. A compile then crosses as two files, the request and the
 * result ([KotlinCompileWire]), exactly as it crosses to the on-device host's forked VM, and
 * [KotlinCompileWorker] is what runs on the far side.
 */
object KotlincNative {
    /** Compile the request in the first file, writing the result to the second; non-zero when no result was written. */
    @Volatile
    var runner: ((request: Path, result: Path) -> Int)? = null

    /** A backend over [runner], or null when the host has none. */
    fun backend(): KotlinCompilerBackend? = runner?.let { NativeKotlinCompiler(it) }
}

/** The far side of [KotlincNative]: one compile, from a request file to a result file. */
object KotlinCompileWorker {
    private val compiler = KotlinJvmCompiler()

    /** Zero when a result was written, whatever the compile's outcome, which is in the result. */
    fun run(requestFile: Path, resultFile: Path): Int = try {
        val result = try {
            compiler.compile(KotlinCompileWire.readRequest(requestFile))
        } catch (t: Throwable) {
            val message = "error: kotlinc threw: ${t.stackTraceToString().lineSequence().take(40).joinToString(" ") { it.trim() }}"
            KotlinCompileResult(false, listOf(message), emptyMap(), listOf(KotlinDiagnostic(KotlinDiagnosticSeverity.ERROR, message)))
        }
        KotlinCompileWire.writeResult(result, resultFile)
        0
    } catch (t: Throwable) {
        1
    }
}

/**
 * [KotlinCompilerBackend] over a compiler the host has linked in. Each compile gets files of its own beside
 * its output, so modules compiling at once never meet.
 *
 * A compiler compiled ahead of time can only do by reflection what it was built knowing it would, and a
 * project may take it down a path nobody traced. That shows as the compiler throwing, or answering nothing,
 * never as a wrong class file: such a compile is done again by [fallback], the compiler in this JVM, which is
 * slow there and always whole.
 */
internal class NativeKotlinCompiler(
    private val run: (request: Path, result: Path) -> Int,
    private val fallback: KotlinCompilerBackend = KotlinJvmCompiler(),
) : KotlinCompilerBackend {

    override fun compile(request: KotlinCompileRequest): KotlinCompileResult = compile(request) {}

    override fun compile(request: KotlinCompileRequest, status: (String) -> Unit): KotlinCompileResult {
        val result = compileLinkedIn(request)
        val broke = result.messages.firstOrNull { it.startsWith(BROKE) || it.startsWith(THREW) } ?: return result
        if (result.success) return result
        status("The fast Kotlin compiler failed (${broke.removePrefix("error: ").take(160)}); compiling in the JVM instead, which is slower.")
        return fallback.compile(request, status)
    }

    private fun compileLinkedIn(request: KotlinCompileRequest): KotlinCompileResult {
        if (request.kotlinSources.isEmpty()) return KotlinCompileResult(true, emptyList())
        val dir = request.outputDir.toAbsolutePath().parent ?: request.outputDir
        val requestFile = try {
            Files.createDirectories(dir)
            Files.createTempFile(dir, "kotlinc", ".request")
        } catch (t: Throwable) {
            return failure("the compile request could not be written: $t")
        }
        val resultFile = requestFile.resolveSibling(requestFile.fileName.toString().removeSuffix(".request") + ".result")
        return try {
            KotlinCompileWire.writeRequest(request, requestFile)
            val code = run(requestFile, resultFile)
            if (code != 0 || !Files.isRegularFile(resultFile)) failure("the compiler answered nothing (code $code)")
            else KotlinCompileWire.readResult(resultFile)
        } catch (t: Throwable) {
            failure("the compiler could not be called: $t")
        } finally {
            runCatching { Files.deleteIfExists(requestFile) }
            runCatching { Files.deleteIfExists(resultFile) }
        }
    }

    private fun failure(why: String): KotlinCompileResult {
        val message = "$BROKE$why"
        return KotlinCompileResult(false, listOf(message), emptyMap(), listOf(KotlinDiagnostic(KotlinDiagnosticSeverity.ERROR, why)))
    }

    private companion object {
        /** A compile that never reached the compiler, or got nothing back from it. */
        const val BROKE = "error: kotlinc: "
        /** The compiler itself throwing, as [KotlinJvmCompiler] and [KotlinCompileWorker] report it. */
        const val THREW = "error: kotlinc threw: "
    }
}
