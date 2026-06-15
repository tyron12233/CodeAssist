package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Reads classpath binaries for the symbol service: locates a class's `.class` bytes, decodes its Kotlin
 * `@Metadata` on demand (cached), and (lazily, once) scans for the extension functions and top-level
 * callables that can't be found class-by-class (`println`, `listOf`, `String.trim`, etc.).
 *
 * The scan is the main cost metadata introduces, so it is made cheap and durable two ways:
 *  1. Skip non-Kotlin jars. A Kotlin library always ships a `META-INF/<name>.kotlin_module`; a plain
 *     Java/Android jar (`android.jar`, etc.) never does. Checking that one entry name skips the whole jar
 *     without reading a single class, so a 40k-class `android.jar` costs an entry scan, not a decode storm.
 *  2. Per-jar, content-keyed cache. Each jar's scan result is cached in memory and (if a [cacheDir] is
 *     given) persisted keyed by name+size+mtime, so an unchanged jar is read from disk once and reused
 *     across launches, never re-scanned.
 */
class ClasspathReader(
    private val containers: List<Path>,
    private val cacheDir: Path? = null,
) : Closeable {

    private val zips = ConcurrentHashMap<String, ZipFile>()
    private val decodeCache = ConcurrentHashMap<String, Holder<KotlinMetadata.Decoded>>()
    private val jarDataCache = ConcurrentHashMap<String, JarScanData>()

    private class Holder<T>(val value: T?)

    private fun zipFor(path: Path): ZipFile? =
        zips.getOrPut(path.toString()) { runCatching { ZipFile(path.toFile()) }.getOrElse { return null } }

    /** Raw bytes of [fqn]'s class file, searching jars then directories. */
    fun classBytes(fqn: String): ByteArray? {
        val rel = fqn.replace('.', '/') + ".class"
        for (c in containers) {
            if (Files.isDirectory(c)) {
                val f = c.resolve(rel)
                if (Files.isRegularFile(f)) return runCatching { Files.readAllBytes(f) }.getOrNull()
            } else {
                val z = zipFor(c) ?: continue
                val e = z.getEntry(rel) ?: continue
                return runCatching { z.getInputStream(e).use { it.readBytes() } }.getOrNull()
            }
        }
        return null
    }

    /** Decode [fqn]'s Kotlin shape (own members + supertypes), or null if absent / not Kotlin. Cached. */
    fun decoded(fqn: String, ctx: KotlinTypeContext?): KotlinMetadata.Decoded? =
        decodeCache.getOrPut(fqn) { Holder(classBytes(fqn)?.let { KotlinMetadata.decode(it, ctx) }) }.value

    @Volatile private var scan: Scan? = null

    class Scan(
        val extensionsByReceiver: Map<String, List<KotlinSymbol>>,
        val topLevelByName: Map<String, List<KotlinSymbol>>,
    )

    /** Lazily scan all classpath jars for extensions + top-level callables. Cached. */
    fun scan(ctx: KotlinTypeContext?): Scan {
        scan?.let { return it }
        synchronized(this) {
            scan?.let { return it }
            val byReceiver = HashMap<String, MutableList<KotlinSymbol>>()
            val byName = HashMap<String, MutableList<KotlinSymbol>>()
            for (c in containers) {
                if (Files.isDirectory(c)) continue // project outputs: the source side already covers these
                val data = jarScanData(c)
                data.extensions.forEach { re ->
                    re.receiverFqn?.let { byReceiver.getOrPut(it) { ArrayList() }.add(re.toSymbol(ctx)) }
                }
                data.topLevel.forEach { re -> byName.getOrPut(re.name) { ArrayList() }.add(re.toSymbol(ctx)) }
            }
            return Scan(byReceiver, byName).also { scan = it }
        }
    }

    /** Per-jar scan result (context-free, cacheable): the main cost of metadata, paid once per jar. */
    private fun jarScanData(path: Path): JarScanData {
        val key = jarKey(path) ?: return JarScanData.EMPTY
        jarDataCache[key]?.let { return it }
        cacheDir?.let { dir ->
            val f = dir.resolve("$key.kxt")
            if (Files.isRegularFile(f)) {
                val d = runCatching { readJarData(f) }.getOrNull()
                if (d != null) { jarDataCache[key] = d; return d }
            }
        }
        val z = zipFor(path) ?: return JarScanData.EMPTY
        val data = if (!hasKotlinModule(z)) JarScanData.EMPTY else scanJar(z)
        jarDataCache[key] = data
        if (data !== JarScanData.EMPTY) cacheDir?.let { dir ->
            runCatching {
                Files.createDirectories(dir)
                writeJarData(dir.resolve("$key.kxt"), data)
            }
        }
        return data
    }

    private fun scanJar(z: ZipFile): JarScanData {
        val ext = ArrayList<RawCallableData>()
        val top = ArrayList<RawCallableData>()
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.name.endsWith(".class")) continue
            val bytes = runCatching { z.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
            val decoded = runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull() ?: continue
            val pkg = e.name.substringBeforeLast('/', "").replace('/', '.').ifEmpty { null }
            decoded.extensions.forEach { s -> ext += RawCallableData.from(s, pkg) }
            decoded.topLevel.forEach { s -> top += RawCallableData.from(s, pkg) }
        }
        return if (ext.isEmpty() && top.isEmpty()) JarScanData.EMPTY else JarScanData(ext, top)
    }

    private fun hasKotlinModule(z: ZipFile): Boolean {
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val n = entries.nextElement().name
            if (n.startsWith("META-INF/") && n.endsWith(".kotlin_module")) return true
        }
        return false
    }

    private fun jarKey(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        return "${path.fileName}_${size}_$mtime".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    override fun close() {
        zips.values.forEach { runCatching { it.close() } }
        zips.clear()
    }

    // --- serializable, context-free intermediate ---

    private class JarScanData(val extensions: List<RawCallableData>, val topLevel: List<RawCallableData>) {
        companion object { val EMPTY = JarScanData(emptyList(), emptyList()) }
    }

    private class RawCallableData(
        val name: String,
        val kind: SymbolKind,
        val receiverFqn: String?,
        val signature: String?,
        val packageName: String?,
        val receiverTypeParam: String?,
        val typeParameters: List<String>,
        val returnType: KotlinType?,
        val paramTypes: List<KotlinType?>,
        val receiverTypeArgs: List<KotlinType>,
    ) {
        // Cached types are context-free; rebind the live context so members()/supertypes() work after reload.
        fun toSymbol(ctx: KotlinTypeContext?): KotlinSymbol = KotlinSymbol(
            name = name,
            kind = kind,
            type = returnType?.withContext(ctx),
            origin = BINARY,
            receiverTypeFqn = receiverFqn,
            signature = signature,
            typeParameters = typeParameters,
            paramTypes = paramTypes.map { it?.withContext(ctx) },
            receiverTypeArgs = receiverTypeArgs.map { it.withContext(ctx) },
            receiverTypeParam = receiverTypeParam,
            packageName = packageName,
        )

        companion object {
            fun from(s: KotlinSymbol, pkg: String?): RawCallableData = RawCallableData(
                s.name, s.kind, s.receiverTypeFqn, s.signature, pkg, s.receiverTypeParam, s.typeParameters,
                s.type as? KotlinType,
                s.paramTypes.map { it as? KotlinType },
                s.receiverTypeArgs.mapNotNull { it as? KotlinType },
            )
        }
    }

    private fun writeJarData(file: Path, data: JarScanData) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(file))).use { out ->
            out.writeInt(FORMAT_VERSION)
            writeList(out, data.extensions)
            writeList(out, data.topLevel)
        }
    }

    private fun readJarData(file: Path): JarScanData? =
        DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { inp ->
            if (inp.readInt() != FORMAT_VERSION) return null
            JarScanData(readList(inp), readList(inp))
        }

    private fun writeList(out: DataOutputStream, list: List<RawCallableData>) {
        out.writeInt(list.size)
        for (r in list) {
            out.writeUTF(r.name)
            out.writeByte(r.kind.ordinal)
            out.writeUTF(r.receiverFqn ?: "")
            out.writeUTF(r.signature ?: "")
            out.writeUTF(r.packageName ?: "")
            out.writeUTF(r.receiverTypeParam ?: "")
            out.writeInt(r.typeParameters.size); r.typeParameters.forEach { out.writeUTF(it) }
            writeType(out, r.returnType)
            out.writeInt(r.paramTypes.size); r.paramTypes.forEach { writeType(out, it) }
            out.writeInt(r.receiverTypeArgs.size); r.receiverTypeArgs.forEach { writeType(out, it) }
        }
    }

    private fun readList(inp: DataInputStream): List<RawCallableData> {
        val n = inp.readInt()
        val out = ArrayList<RawCallableData>(n)
        repeat(n) {
            val name = inp.readUTF()
            val kind = SymbolKind.entries[inp.readByte().toInt()]
            val receiver = inp.readUTF().ifEmpty { null }
            val sig = inp.readUTF().ifEmpty { null }
            val pkg = inp.readUTF().ifEmpty { null }
            val recvParam = inp.readUTF().ifEmpty { null }
            val tps = List(inp.readInt()) { inp.readUTF() }
            val ret = readType(inp)
            val params = List(inp.readInt()) { readType(inp) }
            val recvArgs = List(inp.readInt()) { readType(inp) }.filterNotNull()
            out += RawCallableData(name, kind, receiver, sig, pkg, recvParam, tps, ret, params, recvArgs)
        }
        return out
    }

    /** Recursive, context-free encoding of a [KotlinType] (fqn + nullability + type-param flag + args). */
    private fun writeType(out: DataOutputStream, t: KotlinType?) {
        out.writeBoolean(t != null)
        if (t == null) return
        out.writeUTF(t.qualifiedName)
        out.writeBoolean(t.nullable)
        out.writeBoolean(t.isTypeParameter)
        out.writeBoolean(t.isExtensionFunctionType)
        out.writeInt(t.typeArguments.size)
        t.typeArguments.forEach { writeType(out, it as? KotlinType) }
    }

    private fun readType(inp: DataInputStream): KotlinType? {
        if (!inp.readBoolean()) return null
        val fqn = inp.readUTF()
        val nullable = inp.readBoolean()
        val isTp = inp.readBoolean()
        val isExtFn = inp.readBoolean()
        val n = inp.readInt()
        val args = ArrayList<TypeRef>(n)
        repeat(n) { readType(inp)?.let { args.add(it) } }
        return KotlinType(fqn, args, nullable, context = null, isTypeParameter = isTp, isExtensionFunctionType = isExtFn)
    }

    private companion object {
        const val FORMAT_VERSION = 5 // v5: + isExtensionFunctionType (T.() -> R receiver lambdas)
        val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
