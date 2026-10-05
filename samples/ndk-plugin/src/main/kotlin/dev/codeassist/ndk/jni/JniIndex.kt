package dev.codeassist.ndk.jni

import dev.codeassist.ndk.NdkFacet
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/** A native method and the file it is declared in. */
data class DeclaredNative(val path: String, val method: NativeMethod)

/** A JNI function and the file it is defined in. */
data class DefinedFunction(val path: String, val function: JniFunction)

/**
 * Both sides of one module's JNI binding: the native methods its Java and Kotlin declare, the `Java_…`
 * functions its C and C++ define, and the C and C++ files a new function could go into.
 */
class JniSnapshot(
    val natives: List<DeclaredNative>,
    val functions: List<DefinedFunction>,
    val cppFiles: List<String>,
    /** Some file binds by `RegisterNatives`, so names alone cannot say a method is unimplemented. */
    val registersNatives: Boolean,
) {
    private val definedNames: Set<String> = functions.mapTo(HashSet()) { it.function.name }

    /** The definition implementing [method], by its short or long name, or null. */
    fun implementationOf(method: NativeMethod): DefinedFunction? {
        val short = Jni.shortName(method)
        val long = Jni.longName(method)
        return functions.firstOrNull { it.function.name == short || it.function.name == long }
    }

    fun isImplemented(method: NativeMethod): Boolean =
        Jni.shortName(method) in definedNames || Jni.longName(method) in definedNames

    /** The native method [function] implements, or null when no declaration in the module matches it. */
    fun declarationOf(function: JniFunction): DeclaredNative? =
        natives.firstOrNull { Jni.shortName(it.method) == function.name || Jni.longName(it.method) == function.name }

    /** The other native methods of [method]'s class, which decide whether its symbol needs the long form. */
    fun siblingsOf(method: NativeMethod): List<NativeMethod> =
        natives.map { it.method }.filter { it.className == method.className }
}

/**
 * Reads a module's sources into a [JniSnapshot], file by file, remembering each file's result until it
 * changes on disk. The file being edited is read from its live buffer instead, so a method typed a second ago
 * counts before it is saved.
 */
object JniIndex {

    private class Entry<T>(val modified: Long, val size: Long, val value: T)

    private val jvmCache = ConcurrentHashMap<String, Entry<List<NativeMethod>>>()
    private val cppCache = ConcurrentHashMap<String, Entry<CppJniFile>>()

    private val JVM_EXTENSIONS = setOf("java", "kt")
    private val CPP_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx", "inl")
    private val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "c++")

    fun snapshot(module: Module, facet: NdkFacet?, livePath: String? = null, liveText: String? = null): JniSnapshot {
        val moduleDir = Paths.get(module.dir.path)
        val jvmFiles = module.sourceSets
            .filter { it.scope != DependencyScope.TEST_IMPLEMENTATION }
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles }
            .map { Paths.get(it.dir.path) }
            .distinct()
            .flatMap { files(it, JVM_EXTENSIONS) }
            .distinct()
        val cppFiles = (facet?.sourceDirs ?: emptyList())
            .map { moduleDir.resolve(it).normalize() }
            .distinct()
            .flatMap { files(it, CPP_EXTENSIONS) }
            .distinct()

        val natives = jvmFiles.flatMap { path ->
            val p = path.toString()
            val methods = if (p == livePath && liveText != null) scanJvm(p, liveText)
            else cached(jvmCache, path) { scanJvm(p, it) } ?: emptyList()
            methods.map { DeclaredNative(p, it) }
        }
        var registers = false
        val functions = cppFiles.flatMap { path ->
            val p = path.toString()
            val scan = if (p == livePath && liveText != null) CppJniScanner.scan(liveText)
            else cached(cppCache, path) { CppJniScanner.scan(it) } ?: CppJniFile(emptyList(), false)
            if (scan.registersNatives) registers = true
            scan.functions.map { DefinedFunction(p, it) }
        }
        val sources = cppFiles.filter { it.fileName.toString().substringAfterLast('.').lowercase() in SOURCE_EXTENSIONS }
            .map { it.toString() }
        return JniSnapshot(natives, functions, sources, registers)
    }

    private fun scanJvm(path: String, text: String): List<NativeMethod> =
        if (path.endsWith(".kt")) NativeMethodScanner.scanKotlin(text, path.substringAfterLast('/'))
        else NativeMethodScanner.scanJava(text)

    private fun <T> cached(cache: ConcurrentHashMap<String, Entry<T>>, path: Path, read: (String) -> T): T? {
        val key = path.toString()
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(path) }.getOrDefault(-1L)
        cache[key]?.let { if (it.modified == modified && it.size == size) return it.value }
        val text = runCatching { String(Files.readAllBytes(path)) }.getOrNull() ?: return null
        val value = runCatching { read(text) }.getOrNull() ?: return null
        cache[key] = Entry(modified, size, value)
        return value
    }

    /**
     * Directory listings, reused for a few seconds: the index is asked on every analysis pass, decoration and
     * action check, and walking a source tree each time is the one cost that grows with the project.
     */
    private val listings = ConcurrentHashMap<String, Pair<Long, List<Path>>>()
    private const val LISTING_TTL_MS = 3_000L

    private fun files(root: Path, extensions: Set<String>): List<Path> {
        val key = root.toString() + "|" + extensions.joinToString(",")
        val now = System.currentTimeMillis()
        listings[key]?.let { (at, files) -> if (now - at < LISTING_TTL_MS) return files }
        return walk(root, extensions).also { listings[key] = now to it }
    }

    private fun walk(root: Path, extensions: Set<String>): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root).use { walk ->
                walk.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.').lowercase() in extensions }
                    .collect(Collectors.toList())
            }
        }.getOrDefault(emptyList()).sorted()
    }
}
