package dev.ide.interp

import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** A [VirtualFile] backed by a real path — enough for source-root walking + classpath reads in tests. */
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

/** Write [code] into a fresh temp source dir as `Prog.kt`, returning the dir. */
fun tempProject(code: String): Path {
    val dir = Files.createTempDirectory("interp-core-test")
    Files.writeString(dir.resolve("Prog.kt"), code)
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
            if (p.receiverTypeReference != null) return@forEach
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
