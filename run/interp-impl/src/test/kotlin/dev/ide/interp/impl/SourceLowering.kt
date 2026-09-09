package dev.ide.interp.impl

import dev.ide.interp.api.LoweredProgram
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.DiskVirtualFile
import dev.ide.testkit.TestJars
import dev.ide.testkit.writeSource
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.nio.file.Files

/**
 * Lower [code] to a [LoweredProgram] the way the host does, so a source session can be tested over REAL
 * Kotlin rather than a hand-built tree.
 *
 * The host's own lowering ([dev.ide.interp.api.CodeInterpreter.lower]) needs an open project: its analyzers,
 * its module graph and its indexes. This is the same lowering over one file in a temp directory, which is
 * what makes the sessions testable here instead of only in `:ide-core`.
 */
fun loweredProgram(code: String, entry: String): LoweredProgram {
    val dir = Files.createTempDirectory("interp-impl-test")
    dir.writeSource("Prog.kt", code, trim = false)
    val service = KotlinSymbolService(listOf(DiskVirtualFile(dir)), listOf(TestJars.kotlinStdlib()))
    val kt = KotlinParserHost.parse("Prog.kt", code)
    val parsed = KotlinParsedFile(kt, DiskVirtualFile(dir.resolve("Prog.kt")), 0)
    val resolver = KotlinTreeResolver(kt, parsed, service)
    val functions = kt.declarations.filterIsInstance<KtNamedFunction>()
        .map { resolver.lowerFunction(it) }
        .associateBy { "${it.name}/${it.params.size}" }
    val classes = resolver.lowerClasses()
    val fn = functions.entries.firstOrNull { it.key.substringBeforeLast('/') == entry }?.value
    val type = classes.firstOrNull { it.simpleName == entry || it.fqn == entry }
    return LoweredKotlinProgram(
        functions = functions,
        classes = classes,
        entryFunction = if (type == null) requireNotNull(fn) { "no `$entry` in the program" } else null,
        entryType = if (type == null) null else type,
    )
}
