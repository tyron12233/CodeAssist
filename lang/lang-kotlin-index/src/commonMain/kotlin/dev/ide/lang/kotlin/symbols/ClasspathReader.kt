package dev.ide.lang.kotlin.symbols

import dev.ide.platform.FileSource
import dev.ide.platform.Lock
import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.platform.createDirectories
import dev.ide.platform.deleteFile
import dev.ide.platform.fileInfo
import dev.ide.platform.openFile
import dev.ide.platform.readFile
import dev.ide.platform.writeFileAtomically
import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.ByteArrayDataWriter
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import kotlin.concurrent.Volatile

/**
 * Reads classpath binaries for the symbol service: locates a class's `.class` bytes, decodes its Kotlin
 * `@Metadata` on demand (cached), and (lazily, once) scans for the extension functions and top-level
 * callables that can't be found class-by-class (`println`, `listOf`, `String.trim`, etc.).
 *
 * The scan is the main cost metadata introduces, so it is made cheap and durable two ways:
 *  1. Skip non-Kotlin jars. A Kotlin library always ships a `META-INF/<name>.kotlin_module`; a plain
 *     Java/Android jar (`android.jar`, etc.) never does. Checking that one entry name skips the whole jar
 *     without reading a single class, so a 6,400-class `android.jar` costs an entry scan, not a decode storm.
 *  2. Per-jar, content-keyed cache. Each jar's scan result is cached in memory and (if a `cacheDir` is
 *     given) persisted keyed by name+size+mtime, so an unchanged jar is read from disk once and reused
 *     across launches, never re-scanned.
 *
 * Paths are strings and archives are read through `:kotlin-classfile`, so this runs wherever the decoders
 * above it do. The persisted format is unchanged, byte for byte: `DataWriter` writes what
 * `DataOutputStream` wrote, so a cache directory written by an older build still reads.
 */
/** A library callable user code can never reach: `private` (file-scoped to its library source) or `internal`
 *  (a library is always a DIFFERENT module than the user's source). See `KotlinCallableIndex`'s twin. */
private fun KotlinSymbol.inaccessibleFromAnotherModule(): Boolean =
    Modifier.PRIVATE in modifiers || isInternal

class ClasspathReader(
    private val containers: List<String>,
    private val cacheDir: String? = null,
) : AutoCloseable {

    // A BOUNDED LRU of open jar handles, NOT one-per-jar-forever. Each open archive holds a file descriptor,
    // and a real (Compose) classpath is hundreds of jars; Android's per-process FD limit is ~1024 (lower on
    // older releases), so keeping every jar open exhausts descriptors and any later open — even listing the
    // project tree — fails with "Too many open files". The eldest handle is closed on eviction; an evicted
    // jar is simply reopened on its next access (gated behind the decode and jar-scan caches, so reopens are
    // infrequent).
    //
    // Insertion order IS the recency order here, maintained by removing and re-putting on access, because
    // common Kotlin's LinkedHashMap has no access-ordered mode to inherit from.
    private val zips = LinkedHashMap<String, OpenJar>()

    /** Package directories per jar, learned on its first open and KEPT when its handle is evicted — the
     *  miss filter that stops [readEntry] reopening every container. See [withZip]. */
    private val packageDirs = HashMap<String, Set<String>>()

    /**
     * Each container paired with whether it is a DIRECTORY, decided once.
     *
     * [readEntry] asked `fileInfo(c)` per container per lookup — a stat syscall for every entry on the
     * classpath, every time a type is probed, to re-answer a question that cannot change while a reader is
     * alive. On a 68-entry classpath that is 68 syscalls to look up one class, and `isKnownType` runs per
     * member access. Classified lazily so construction stays cheap.
     */
    private val containerKinds: List<Pair<String, Boolean>> by lazy {
        containers.map { it to (fileInfo(it)?.isDirectory == true) }
    }
    private val zipLock = Lock()

    // Guarded rather than concurrent maps: common Kotlin has no ConcurrentHashMap, and the analysis calls
    // that reach here are already serialized onto one dispatcher, so an uncontended lock costs nothing.
    private val cacheLock = Lock()
    private val decodeCache = HashMap<String, Holder<KotlinMetadata.Decoded>>()
    private val jarDataCache = HashMap<String, JarScanData>()

    private class Holder<T>(val value: T?)

    private class OpenJar(val source: FileSource, val archive: ZipArchive)

    /**
     * Run [block] with an open archive for [path] from the bounded LRU, opening it if absent. The block
     * runs while the LRU lock is held, so the handle it reads can never be the one a concurrent caller
     * evicts and closes. Returns null when the jar can't be opened (the block may also return null).
     */
    private fun <T> withZip(path: String, pkg: String, block: (ZipArchive) -> T?): T? = zipLock.withLock {
        // The handle LRU holds [MAX_OPEN_ZIPS]; a real classpath is bigger than that, and [readEntry] asks
        // EVERY container for every lookup. So a lookup that misses -- which is what `isKnownType` asks on
        // the editor's hot path, per member access -- evicted and reopened the jars past the bound, and each
        // reopen re-parses the whole central directory into a hash map. Measured on a 68-jar classpath: a
        // single analysis pass spent minutes in `ZipArchive.<init>`, never reaching the files it was meant
        // to check.
        //
        // The package directories survive eviction, so after a jar has been opened ONCE, a miss against it
        // is a set lookup. Packages, not entry names: a jar has hundreds of the former and tens of thousands
        // of the latter, and the question a lookup asks ("could this jar hold `androidx/compose/ui/Foo`?")
        // is answered by the directory alone.
        packageDirs[path]?.let { if (pkg !in it) return@withLock null }
        val jar = zips.remove(path) ?: openJar(path)
        if (jar == null) return@withLock null
        zips[path] = jar
        if (path !in packageDirs) {
            packageDirs[path] = jar.archive.entries.mapTo(HashSet()) { it.name.substringBeforeLast('/', "") }
        }
        while (zips.size > MAX_OPEN_ZIPS) {
            val eldest = zips.keys.firstOrNull() ?: break
            zips.remove(eldest)?.source?.close()
        }
        block(jar.archive)
    }

    private fun openJar(path: String): OpenJar? {
        val source = openFile(path) ?: return null
        val archive = ZipArchive.open(source)
        if (archive == null) {
            source.close()
            return null
        }
        return OpenJar(source, archive)
    }

    /** Raw bytes of [fqn]'s class file, searching jars then directories. A nested type may be written with
     *  `.` (`android.R.string`) instead of the binary `$` (`android/R$string`), so when the direct path
     *  misses, retry converting trailing `.`-boundaries to `$` (right to left) — handles arbitrary nesting. */
    fun classBytes(fqn: String): ByteArray? {
        readEntry(fqn.replace('.', '/') + ".class")?.let { return it }
        var dot = fqn.lastIndexOf('.')
        val chars = fqn.toCharArray()
        while (dot > 0) {
            chars[dot] = '$'
            readEntry(chars.concatToString().replace('.', '/') + ".class")?.let { return it }
            dot = fqn.lastIndexOf('.', dot - 1)
        }
        return null
    }

    private fun readEntry(rel: String): ByteArray? {
        for ((c, isDir) in containerKinds) {
            if (isDir) {
                val f = "$c/$rel"
                if (fileInfo(f)?.isDirectory == false) readFile(f)?.let { return it }
            } else {
                val bytes = withZip(c, rel.substringBeforeLast('/', "")) { archive ->
                    val entry = archive.entry(rel) ?: return@withZip null
                    archive.read(entry)
                }
                if (bytes != null) return bytes
            }
        }
        return null
    }

    /** Decode [fqn]'s Kotlin shape (own members + supertypes), or null if absent / not Kotlin. Cached. */
    fun decoded(fqn: String, ctx: KotlinTypeContext?): KotlinMetadata.Decoded? =
        cacheLock.withLock {
            decodeCache.getOrPut(fqn) { Holder(classBytes(fqn)?.let { KotlinMetadata.decode(it, ctx) }) }
        }.value

    @Volatile
    private var scan: Scan? = null

    class Scan(
        val extensionsByReceiver: Map<String, List<KotlinSymbol>>,
        val topLevelByName: Map<String, List<KotlinSymbol>>,
    )

    /** Lazily scan all classpath jars for extensions + top-level callables. Cached. */
    fun scan(ctx: KotlinTypeContext?): Scan {
        scan?.let { return it }
        return scanLock.withLock {
            scan?.let { return@withLock it }
            val byReceiver = HashMap<String, MutableList<KotlinSymbol>>()
            val byName = HashMap<String, MutableList<KotlinSymbol>>()
            for (c in containers) {
                // project outputs: the source side already covers these
                if (fileInfo(c)?.isDirectory != false) continue
                val data = jarScanData(c)
                data.extensions.forEach { re ->
                    re.receiverFqn?.let { byReceiver.getOrPut(it) { ArrayList() }.add(re.toSymbol(ctx)) }
                }
                data.topLevel.forEach { re ->
                    byName.getOrPut(re.name) { ArrayList() }.add(re.toSymbol(ctx))
                }
            }
            Scan(byReceiver, byName).also { scan = it }
        }
    }

    private val scanLock = Lock()

    /** Per-jar scan result (context-free, cacheable): the main cost of metadata, paid once per jar. */
    private fun jarScanData(path: String): JarScanData {
        val key = jarKey(path) ?: return JarScanData.EMPTY
        cacheLock.withLock { jarDataCache[key] }?.let { return it }
        cacheDir?.let { dir ->
            val f = "$dir/$key.kxt"
            if (fileInfo(f)?.isDirectory == false) {
                val d = runCatching { readJarData(f) }.getOrNull()
                if (d != null) {
                    cacheLock.withLock { jarDataCache[key] = d }
                    return d
                }
            }
        }
        // Open a fresh handle and close it immediately: the full-jar scan runs once per jar (then the .kxt /
        // jarDataCache memoize it), so it should not occupy a slot in the LRU of hot read handles.
        val jar = openJar(path) ?: return JarScanData.EMPTY
        val data = try {
            if (!hasKotlinModule(jar.archive)) JarScanData.EMPTY else scanJar(jar.archive)
        } finally {
            jar.source.close()
        }
        cacheLock.withLock { jarDataCache[key] = data }
        if (data !== JarScanData.EMPTY) {
            cacheDir?.let { dir ->
                runCatching {
                    createDirectories(dir)
                    writeJarData("$dir/$key.kxt", data)
                }
            }
        }
        return data
    }

    private fun scanJar(archive: ZipArchive): JarScanData {
        val ext = ArrayList<RawCallableData>()
        val top = ArrayList<RawCallableData>()
        for (entry in archive.entries) {
            if (!entry.name.endsWith(".class")) continue
            val bytes = archive.read(entry) ?: continue
            val decoded = runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull() ?: continue
            val pkg = entry.name.substringBeforeLast('/', "").replace('/', '.').ifEmpty { null }
            // The .class being scanned IS the JVM facade these top-level/extension functions compile into
            // (`kotlin/io/ConsoleKt` for println) — exactly what the interpreter reflects into.
            val facade = entry.name.removeSuffix(".class").replace('/', '.')
            // Skip library `private`/`internal` callables — never accessible from the user's (other) module,
            // so they must not leak into completion/resolution (mirrors KotlinCallableIndex, the on-device
            // path).
            decoded.extensions.forEach { s ->
                if (!s.inaccessibleFromAnotherModule()) ext += RawCallableData.from(s, pkg, facade)
            }
            decoded.topLevel.forEach { s ->
                if (!s.inaccessibleFromAnotherModule()) top += RawCallableData.from(s, pkg, facade)
            }
        }
        return if (ext.isEmpty() && top.isEmpty()) JarScanData.EMPTY else JarScanData(ext, top)
    }

    private fun hasKotlinModule(archive: ZipArchive): Boolean =
        archive.entries.any { it.name.startsWith("META-INF/") && it.name.endsWith(".kotlin_module") }

    private fun jarKey(path: String): String? {
        val info = fileInfo(path) ?: return null
        if (info.isDirectory) return null
        val name = path.substringAfterLast('/')
        return "${name}_${info.size}_${info.lastModified}".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /** Drop the decoded metadata and jar-scan caches and close every open handle; each refills on demand. */
    fun releaseMemory() {
        cacheLock.withLock {
            decodeCache.clear()
            jarDataCache.clear()
        }
        zipLock.withLock {
            zips.values.forEach { runCatching { it.source.close() } }
            zips.clear()
        }
    }

    override fun close() = zipLock.withLock {
        zips.values.forEach { runCatching { it.source.close() } }
        zips.clear()
    }

    // --- serializable, context-free intermediate ---

    private class JarScanData(
        val extensions: List<RawCallableData>,
        val topLevel: List<RawCallableData>,
    ) {
        companion object {
            val EMPTY = JarScanData(emptyList(), emptyList())
        }
    }

    private class RawCallableData(
        val name: String,
        val kind: SymbolKind,
        val receiverFqn: String?,
        val signature: String?,
        val packageName: String?,
        val receiverTypeParam: String?,
        val typeParameters: List<String>,
        val typeParamBoundNames: List<String?>,
        val returnType: KotlinType?,
        val paramTypes: List<KotlinType?>,
        val receiverTypeArgs: List<KotlinType>,
        val declaringClassFqn: String?,
        val paramNames: List<String>,
        val isComposable: Boolean,
        val isInline: Boolean,
        val isInfix: Boolean,
        val isSuspend: Boolean,
        val varargParamIndex: Int,
        val paramHasDefault: List<Boolean>,
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
            typeParamBoundNames = typeParamBoundNames,
            paramTypes = paramTypes.map { it?.withContext(ctx) },
            paramNames = paramNames,
            receiverTypeArgs = receiverTypeArgs.map { it.withContext(ctx) },
            receiverTypeParam = receiverTypeParam,
            packageName = packageName,
            declaringClassFqn = declaringClassFqn,
            isComposable = isComposable,
            isInline = isInline,
            isInfix = isInfix,
            isSuspend = isSuspend,
            varargParamIndex = varargParamIndex,
            paramHasDefault = paramHasDefault,
        )

        companion object {
            fun from(s: KotlinSymbol, pkg: String?, facade: String?): RawCallableData =
                RawCallableData(
                    s.name,
                    s.kind,
                    s.receiverTypeFqn,
                    s.signature,
                    pkg,
                    s.receiverTypeParam,
                    s.typeParameters,
                    s.typeParamBoundNames,
                    s.type as? KotlinType,
                    s.paramTypes.map { it as? KotlinType },
                    s.receiverTypeArgs.filterIsInstance<KotlinType>(),
                    // The decode already set the facade for a multi-file class part; else use the .class entry.
                    s.declaringClassFqn ?: facade,
                    s.paramNames,
                    s.isComposable,
                    s.isInline,
                    s.isInfix,
                    s.isSuspend,
                    s.varargParamIndex,
                    s.paramHasDefault,
                )
        }
    }

    /** Write [data] atomically: a reader must see either the whole entry or none of it. Several processes can
     *  share one cache dir (the IDE and the preview process; parallel test workers), so a half-written file
     *  is a real state — and a torn read is worse than a miss, since the length prefixes it decodes are then
     *  garbage. The write goes to a unique sibling and is moved into place. */
    private fun writeJarData(file: String, data: JarScanData) {
        val out = ByteArrayDataWriter()
        out.writeInt(FORMAT_VERSION)
        writeList(out, data.extensions)
        writeList(out, data.topLevel)
        // A failed write is "no cache entry" to the caller, never a failure to scan.
        if (!writeFileAtomically(file, out.toByteArray())) deleteFile(file)
    }

    private fun readJarData(file: String): JarScanData? {
        val inp = ByteArrayDataReader(readFile(file) ?: return null)
        if (inp.readInt() != FORMAT_VERSION) return null
        return JarScanData(readList(inp), readList(inp))
    }

    private fun writeList(out: DataWriter, list: List<RawCallableData>) {
        out.writeInt(list.size)
        for (r in list) {
            out.writeUTF(r.name)
            out.writeByte(r.kind.ordinal)
            out.writeUTF(r.receiverFqn ?: "")
            out.writeUTF(r.signature ?: "")
            out.writeUTF(r.packageName ?: "")
            out.writeUTF(r.receiverTypeParam ?: "")
            out.writeInt(r.typeParameters.size); r.typeParameters.forEach { out.writeUTF(it) }
            out.writeInt(r.typeParamBoundNames.size); r.typeParamBoundNames.forEach { out.writeUTF(it ?: "") }
            writeType(out, r.returnType)
            out.writeInt(r.paramTypes.size); r.paramTypes.forEach { writeType(out, it) }
            out.writeInt(r.receiverTypeArgs.size); r.receiverTypeArgs.forEach { writeType(out, it) }
            out.writeUTF(r.declaringClassFqn ?: "")
            out.writeInt(r.paramNames.size); r.paramNames.forEach { out.writeUTF(it) }
            out.writeBoolean(r.isComposable)
            out.writeBoolean(r.isInline)
            out.writeBoolean(r.isInfix)
            out.writeBoolean(r.isSuspend)
            out.writeInt(r.varargParamIndex)
            out.writeInt(r.paramHasDefault.size); r.paramHasDefault.forEach { out.writeBoolean(it) }
        }
    }

    private fun readList(inp: DataReader): List<RawCallableData> {
        val n = inp.readInt()
        val out = ArrayList<RawCallableData>(n)
        repeat(n) {
            val name = inp.readUTF()
            val kind = SymbolKind.entries[inp.readByte()]
            val receiver = inp.readUTF().ifEmpty { null }
            val sig = inp.readUTF().ifEmpty { null }
            val pkg = inp.readUTF().ifEmpty { null }
            val recvParam = inp.readUTF().ifEmpty { null }
            val tps = List(inp.readInt()) { inp.readUTF() }
            val boundNames = List(inp.readInt()) { inp.readUTF().ifEmpty { null } }
            val ret = readType(inp)
            val params = List(inp.readInt()) { readType(inp) }
            val recvArgs = List(inp.readInt()) { readType(inp) }.filterNotNull()
            val declaringFqn = inp.readUTF().ifEmpty { null }
            val paramNames = List(inp.readInt()) { inp.readUTF() }
            val isComposable = inp.readBoolean()
            val isInline = inp.readBoolean()
            val isInfix = inp.readBoolean()
            val isSuspend = inp.readBoolean()
            val varargIdx = inp.readInt()
            val paramHasDefault = List(inp.readInt()) { inp.readBoolean() }
            out += RawCallableData(
                name, kind, receiver, sig, pkg, recvParam, tps, boundNames, ret, params, recvArgs,
                declaringFqn, paramNames, isComposable, isInline, isInfix, isSuspend, varargIdx,
                paramHasDefault,
            )
        }
        return out
    }

    /** Recursive, context-free encoding of a [KotlinType] (fqn + nullability + type-param flag + args). */
    private fun writeType(out: DataWriter, t: KotlinType?) {
        out.writeBoolean(t != null)
        if (t == null) return
        out.writeUTF(t.qualifiedName)
        out.writeBoolean(t.nullable)
        out.writeBoolean(t.isTypeParameter)
        out.writeBoolean(t.isExtensionFunctionType)
        out.writeBoolean(t.isComposable)
        out.writeInt(t.typeArguments.size)
        t.typeArguments.forEach { writeType(out, it as? KotlinType) }
    }

    private fun readType(inp: DataReader): KotlinType? {
        if (!inp.readBoolean()) return null
        val fqn = inp.readUTF()
        val nullable = inp.readBoolean()
        val isTp = inp.readBoolean()
        val isExtFn = inp.readBoolean()
        val isComposable = inp.readBoolean()
        val n = inp.readInt()
        val args = ArrayList<TypeRef>(n)
        repeat(n) { readType(inp)?.let { args.add(it) } }
        return KotlinType(
            fqn, args, nullable, context = null, isTypeParameter = isTp,
            isExtensionFunctionType = isExtFn, isComposable = isComposable,
        )
    }

    private companion object {
        const val FORMAT_VERSION =
            14 // v14: + RawCallableData.isInfix (infix-function completion in the operator slot)

        // Cap on simultaneously-open jar handles (file descriptors). Small: the hot working set during
        // completion is a handful of jars, and reopening an evicted one is cheap behind the decode caches.
        const val MAX_OPEN_ZIPS = 24
        val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
