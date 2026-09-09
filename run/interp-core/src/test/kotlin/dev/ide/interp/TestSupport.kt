package dev.ide.interp

import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.DiskVirtualFile
import dev.ide.testkit.TestJars
import dev.ide.testkit.writeSource
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import java.nio.file.Files
import java.nio.file.Path

/** A [dev.ide.vfs.VirtualFile] backed by a real path — enough for source-root walking + classpath reads. */
typealias DiskFile = DiskVirtualFile

/** The kotlin-stdlib jar on the test classpath (the one carrying `kotlin/Pair.class`). */
fun stdlibJarPath(): Path = TestJars.kotlinStdlib()

/** Write [code] into a fresh temp source dir as `Prog.kt`, returning the dir. */
fun tempProject(code: String): Path {
    val dir = Files.createTempDirectory("interp-core-test")
    dir.writeSource("Prog.kt", code, trim = false)
    return dir
}

/**
 * Lower every top-level function in [code] to a [ResolvedFunction], keyed `"name/arity"`. The code is
 * written to disk so the resolver's cross-function call resolution (which reads the source model) finds
 * sibling functions.
 */
fun lowerProgram(code: String): Map<String, ResolvedFunction> = lowerProgramFull(code).first

/** Lower every top-level function (keyed `"name/arity"`) AND every source class/object/enum in [code]. */
fun lowerProgramFull(code: String): Pair<Map<String, ResolvedFunction>, List<ResolvedClass>> {
    val dir = tempProject(code)
    val service = KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath()))
    val kt = KotlinParserHost.parse("Prog.kt", code)
    val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Prog.kt")), 0)
    val resolver = KotlinTreeResolver(kt, parsed, service)
    val functions = buildMap {
        kt.declarations.filterIsInstance<KtNamedFunction>().forEach { fn ->
            val f = resolver.lowerFunction(fn)
            put("${f.name}/${f.params.size}", f)
        }
        // Mirror KotlinPreviewLowering: top-level source `val`/`var` become synthetic `name/0` getters, so a
        // read of one interprets its initializer (there is no compiled facade to reflect).
        kt.declarations.filterIsInstance<KtProperty>().forEach { p ->
            val name = p.name ?: return@forEach
            val hasValue = p.initializer != null || p.getter?.bodyExpression != null || p.getter?.bodyBlockExpression != null
            if (!hasValue || containsKey("$name/0")) return@forEach
            put("$name/0", resolver.lowerTopLevelProperty(p))
        }
    }
    return functions to resolver.lowerClasses()
}

/** Lower [code], then interpret the function [entry] (`"name/arity"`) with [args]. */
fun runProgram(code: String, entry: String, args: List<Any?>): Any? {
    val (functions, classes) = lowerProgramFull(code)
    val target = functions[entry] ?: error("no function `$entry`; have ${functions.keys}")
    return Interpreter(functions, classes = classes).call(target, args)
}
