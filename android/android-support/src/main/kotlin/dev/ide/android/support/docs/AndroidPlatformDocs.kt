package dev.ide.android.support.docs

import dev.ide.lang.resolve.SourceDocProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The Android platform's parameter names and javadoc, bundled with the IDE (`android-docs.bin`), so framework
 * and `java.*` APIs show real parameter names and docs without downloading the platform sources. A
 * [SourceDocProvider] the host consults only after the real sources (index and live parse) have missed.
 *
 * Generated from `sources/android-<level>` by `:android-sdk-metadata:generateAndroidDocs`, keeping only the
 * classes and members `android.jar` exposes. Grouped by package, each package one deflated block, and only a
 * looked-up package is ever inflated, so the pack stays on disk ([bundled] copies it to a cache dir) and costs
 * the heap only what a few recently used packages decode to.
 */
class AndroidPlatformDocs private constructor(
    /** The API level of the sources the pack was generated from. */
    val platformLevel: Int,
    private val directory: Map<String, Block>,
    private val dataStart: Long,
    private val read: (offset: Long, length: Int) -> ByteArray,
) : SourceDocProvider {

    /** A class's docs: its own javadoc and its methods (constructors under the class's simple name). */
    class ClassDocs(val pkg: String, val name: String, val doc: String?, val methods: List<MethodDocs>) {
        val fqn: String get() = if (pkg.isEmpty()) name else "$pkg.$name"
    }

    /** One method overload: its parameter names (so its arity) and raw javadoc. */
    class MethodDocs(val name: String, val names: List<String>, val doc: String?)

    private class Block(val offset: Long, val length: Int)

    /** Recently decoded packages, by package name: relative class name -> docs. */
    private val decoded = object : LinkedHashMap<String, Map<String, ClassDocs>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, ClassDocs>>?) = size > DECODED_PACKAGES
    }

    /** The docs of [fqn] (`android.app.Activity`, nested as `android.view.View.OnClickListener`), or null. */
    fun classDocs(fqn: String): ClassDocs? {
        val name = fqn.replace('$', '.')
        var dot = name.lastIndexOf('.')
        while (dot > 0) {
            val pkg = name.substring(0, dot)
            if (pkg in directory) return pkg(pkg)?.get(name.substring(dot + 1))
            dot = name.lastIndexOf('.', dot - 1)
        }
        return null
    }

    private fun pkg(pkg: String): Map<String, ClassDocs>? {
        synchronized(decoded) { decoded[pkg]?.let { return it } }
        val block = directory[pkg] ?: return null
        val classes = runCatching { decodeBlock(pkg, read(dataStart + block.offset, block.length)) }.getOrNull() ?: return null
        synchronized(decoded) { decoded[pkg] = classes }
        return classes
    }

    private fun overload(declaringFqn: String, methodName: String, arity: Int): MethodDocs? {
        val candidates = classDocs(declaringFqn)?.methods?.filter { it.name == methodName }.orEmpty()
        return candidates.firstOrNull { it.names.size == arity } ?: candidates.firstOrNull()
    }

    override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? =
        overload(declaringFqn, methodName, arity)?.let { SourceDocProvider.MethodDoc(it.names, it.doc?.let(::cleanJavadoc)) }

    override fun methodRaw(declaringFqn: String, methodName: String, arity: Int): String? =
        overload(declaringFqn, methodName, arity)?.doc

    override fun classDoc(fqn: String): String? = classDocs(fqn)?.doc?.let(::cleanJavadoc)

    override fun classDocRaw(fqn: String): String? = classDocs(fqn)?.doc

    companion object {
        private const val MAGIC = 0x41444F43 // "ADOC"
        private const val VERSION = 1
        private const val DECODED_PACKAGES = 24

        /** The resource the pack ships as, next to `android-api-levels.bin`. */
        const val RESOURCE = "/android-docs.bin"

        @Volatile private var bundled: AndroidPlatformDocs? = null
        @Volatile private var bundledMissing = false

        /**
         * The pack bundled with the IDE, copied once into [cacheDir] and read from there, or null when the
         * resource is absent. Loaded once per process.
         */
        fun bundled(cacheDir: Path): AndroidPlatformDocs? {
            bundled?.let { return it }
            if (bundledMissing) return null
            synchronized(this) {
                bundled?.let { return it }
                val docs = runCatching { extract(cacheDir)?.let(::open) }.getOrNull()
                if (docs == null) bundledMissing = true else bundled = docs
                return docs
            }
        }

        /** Copy the bundled resource to [cacheDir] unless an identical copy is there. Null without the resource. */
        private fun extract(cacheDir: Path): Path? {
            val bytes = AndroidPlatformDocs::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() } ?: return null
            val target = cacheDir.resolve("android-docs.bin")
            if (Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong()) return target
            Files.createDirectories(cacheDir)
            val tmp = Files.createTempFile(cacheDir, "android-docs", ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        }

        /** Open a pack file, reading each package block from disk when it is first needed. */
        fun open(file: Path): AndroidPlatformDocs {
            val raf = RandomAccessFile(file.toFile(), "r")
            val header = DataInputStream(java.nio.channels.Channels.newInputStream(raf.channel).buffered())
            return readHeader(header) { level, dir, headerSize ->
                AndroidPlatformDocs(level, dir, headerSize) { offset, length ->
                    synchronized(raf) {
                        ByteArray(length).also { raf.seek(offset); raf.readFully(it) }
                    }
                }
            }
        }

        /** Read a pack held in memory (tests, tools). */
        fun read(bytes: ByteArray): AndroidPlatformDocs =
            readHeader(DataInputStream(ByteArrayInputStream(bytes))) { level, dir, headerSize ->
                AndroidPlatformDocs(level, dir, headerSize) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
            }

        private fun readHeader(input: DataInputStream, build: (Int, Map<String, Block>, Long) -> AndroidPlatformDocs): AndroidPlatformDocs {
            var size = 0L
            fun int(): Int = input.readInt().also { size += 4 }
            fun utf(): String = input.readUTF().also { size += 2 + it.toByteArray(Charsets.UTF_8).size }
            require(int() == MAGIC) { "not an Android docs pack" }
            require(int() == VERSION) { "unsupported Android docs pack version" }
            val level = int()
            val count = int()
            val dir = HashMap<String, Block>(count * 2)
            repeat(count) {
                val name = utf()
                dir[name] = Block(int().toLong(), int())
            }
            return build(level, dir, size)
        }

        private fun decodeBlock(pkg: String, block: ByteArray): Map<String, ClassDocs> {
            val data = DataInputStream(InflaterInputStream(ByteArrayInputStream(block)).buffered())
            val classes = HashMap<String, ClassDocs>()
            repeat(data.readInt()) {
                val name = data.readUTF()
                val doc = readText(data)
                val methods = List(data.readInt()) {
                    val methodName = data.readUTF()
                    val names = List(data.readUnsignedByte()) { data.readUTF() }
                    MethodDocs(methodName, names, readText(data))
                }
                classes[name] = ClassDocs(pkg, name, doc, methods)
            }
            return classes
        }

        /** Write a pack of [classes], generated from the sources of API [platformLevel]. */
        fun write(platformLevel: Int, classes: List<ClassDocs>): ByteArray {
            val byPackage = classes.groupBy { it.pkg }.toSortedMap()
            val blocks = byPackage.mapValues { (_, list) -> encodeBlock(list.sortedBy { it.name }) }
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(MAGIC); d.writeInt(VERSION); d.writeInt(platformLevel); d.writeInt(blocks.size)
                var offset = 0
                for ((pkg, block) in blocks) {
                    d.writeUTF(pkg); d.writeInt(offset); d.writeInt(block.size)
                    offset += block.size
                }
                for (block in blocks.values) d.write(block)
            }
            return out.toByteArray()
        }

        private fun encodeBlock(classes: List<ClassDocs>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION))).use { d ->
                d.writeInt(classes.size)
                for (c in classes) {
                    d.writeUTF(c.name)
                    writeText(d, c.doc)
                    d.writeInt(c.methods.size)
                    for (m in c.methods) {
                        d.writeUTF(m.name)
                        d.writeByte(m.names.size)
                        m.names.forEach(d::writeUTF)
                        writeText(d, m.doc)
                    }
                }
            }
            return out.toByteArray()
        }

        /** Docs can exceed `writeUTF`'s 64 KB, so text is length-prefixed UTF-8; -1 for none. */
        private fun writeText(d: DataOutputStream, text: String?) {
            if (text == null) { d.writeInt(-1); return }
            val bytes = text.toByteArray(Charsets.UTF_8)
            d.writeInt(bytes.size); d.write(bytes)
        }

        private fun readText(d: DataInputStream): String? {
            val n = d.readInt()
            if (n < 0) return null
            return String(ByteArray(n).also(d::readFully), Charsets.UTF_8)
        }

        /** Raw javadoc as plain text: comment markers and `@` tag lines dropped (as the source-doc index does). */
        internal fun cleanJavadoc(raw: String): String = raw.lineSequence()
            .map {
                it.trim().removePrefix("/**").removePrefix("/*")
                    .let { l -> if (l.endsWith("*/")) l.dropLast(2) else l }
                    .trim().removePrefix("*").trim()
            }
            .filterNot { it.startsWith("@") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(2000)
    }
}
