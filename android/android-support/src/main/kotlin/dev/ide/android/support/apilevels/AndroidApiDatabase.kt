package dev.ide.android.support.apilevels

import dev.ide.android.support.AndroidApiLevels
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.PlatformApiLevels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The platform API-level table ([PlatformApiLevels]) read from the bundled `android-api-levels.bin`, which
 * [ApiVersionsXml] and [AndroidApiDatabase.write] produce from a platform's `data/api-versions.xml`.
 *
 * Kept compact in memory: the decompressed table stays one byte array, only a class-name directory is built
 * up front, and a class's members are decoded the first time that class is asked about.
 */
class AndroidApiDatabase private constructor(
    override val platformLevel: Int,
    private val bytes: ByteArray,
    /** Dotted class name (`android.os.Build.VERSION`) to the offset of that class's record in [bytes]. */
    private val directory: Map<String, Int>,
) : PlatformApiLevels {

    /** A class record as written by the generator, before members are decoded. */
    class ClassRecord(
        val internalName: String,
        val since: Int,
        val deprecatedIn: Int,
        val removedIn: Int,
        val supers: List<String>,
        val methods: List<MemberRecord>,
        val fields: List<MemberRecord>,
    )

    /** A method (`name(desc)ret`) or field (`name`) record. A [since] of 0 means "the class's". */
    class MemberRecord(
        val key: String,
        val since: Int,
        val deprecatedIn: Int,
        val removedIn: Int,
        val desugared: Boolean = false,
    )

    private class Decoded(val record: ClassRecord) {
        val methodsByName: Map<String, List<MemberRecord>> =
            record.methods.groupBy { it.key.substring(0, it.key.indexOf('(')) }
        val fieldsByName: Map<String, MemberRecord> = record.fields.associateBy { it.key }
    }

    private val decoded = ConcurrentHashMap<String, Decoded>()

    /**
     * Every D8-backported method in the table as `owner#name(desc)ret`, the owner by internal name (the form
     * [ApiVersionsXml.parse] takes). Decodes each record without caching it, for a one-off table rebuild.
     */
    fun desugaredMethods(): Set<String> {
        val out = HashSet<String>()
        for (offset in directory.values) {
            val r = readClass(DataInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset)))
            r.methods.filter { it.desugared }.mapTo(out) { "${r.internalName}#${it.key}" }
        }
        return out
    }

    private fun lookup(className: String): Decoded? {
        val key = canonical(className)
        decoded[key]?.let { return it }
        val offset = directory[key] ?: return null
        val d = Decoded(readClass(DataInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset))))
        decoded.putIfAbsent(key, d)
        return d
    }

    override fun classLevel(className: String): ApiLevel? =
        lookup(className)?.record?.let { ApiLevel(maxOf(it.since, 1), it.deprecatedIn, it.removedIn) }

    override fun methodLevel(ownerClass: String, name: String, descriptor: String?, arity: Int): ApiLevel? =
        findMember(ownerClass, HashSet()) { cls ->
            val overloads = cls.methodsByName[name] ?: return@findMember null
            val matching = when {
                descriptor != null -> overloads.filter { it.key == name + descriptor }
                arity >= 0 -> overloads.filter { arityOf(it.key, name.length) == arity }
                else -> overloads
            }
            if (matching.isEmpty()) return@findMember null
            merge(matching.map { levelOf(it, cls.record) })
        }

    override fun fieldLevel(ownerClass: String, name: String): ApiLevel? =
        findMember(ownerClass, HashSet()) { cls -> cls.fieldsByName[name]?.let { levelOf(it, cls.record) } }

    /** The member on [className], or the first supertype (breadth-first) that declares it. */
    private fun findMember(className: String, seen: MutableSet<String>, pick: (Decoded) -> ApiLevel?): ApiLevel? {
        val queue = ArrayDeque<String>().apply { add(className) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(canonical(next))) continue
            val cls = lookup(next) ?: continue
            pick(cls)?.let { return it }
            queue.addAll(cls.record.supers)
        }
        return null
    }

    companion object {
        private const val MAGIC = 0x41415049 // "AAPI"
        private const val VERSION = 1

        /** The resource the bundled table ships as, next to `android-sdk-metadata.txt`. */
        const val RESOURCE = "/android-api-levels.bin"

        @Volatile private var bundled: AndroidApiDatabase? = null
        @Volatile private var bundledMissing = false

        /** The table bundled with the IDE, or null when the resource is absent. Loaded once. */
        fun bundled(): AndroidApiDatabase? {
            bundled?.let { return it }
            if (bundledMissing) return null
            synchronized(this) {
                bundled?.let { return it }
                val stream = AndroidApiDatabase::class.java.getResourceAsStream(RESOURCE)
                if (stream == null) {
                    bundledMissing = true
                    return null
                }
                return stream.use { read(it) }.also { bundled = it }
            }
        }

        private val platformTables = ConcurrentHashMap<Path, AndroidApiDatabase>()

        /**
         * The table for the platform [androidJar] belongs to. A platform newer than the bundled table (an
         * installed `android-37` when the IDE ships 36) is read from its own `data/api-versions.xml`, once per
         * session, with the D8-backported methods carried over from the bundled table. Anything else, or a
         * platform whose file cannot be read, gets the [bundled] table.
         */
        fun forPlatform(androidJar: Path?): AndroidApiDatabase? {
            val base = bundled() ?: return null
            val dir = androidJar?.parent ?: return base
            if (AndroidApiLevels.levelOfPlatformJar(androidJar) <= base.platformLevel) return base
            val xml = dir.resolve("data").resolve("api-versions.xml")
            if (!Files.isRegularFile(xml)) return base
            return platformTables.getOrPut(dir) {
                runCatching {
                    val parsed = Files.newInputStream(xml).use { ApiVersionsXml.parse(it, base.desugaredMethods()) }
                    read(write(parsed.platformLevel, parsed.classes).inputStream())
                }.getOrDefault(base)
            }
        }

        /** `android/os/Build$VERSION`, `android.os.Build$VERSION` and `android.os.Build.VERSION` all key alike. */
        fun canonical(className: String): String {
            if (className.indexOf('/') < 0 && className.indexOf('$') < 0) return className
            return className.replace('/', '.').replace('$', '.')
        }

        private fun levelOf(member: MemberRecord, owner: ClassRecord): ApiLevel = ApiLevel(
            since = maxOf(if (member.since > 0) member.since else owner.since, 1),
            deprecatedIn = if (member.deprecatedIn > 0) member.deprecatedIn else owner.deprecatedIn,
            removedIn = if (member.removedIn > 0) member.removedIn else owner.removedIn,
            desugared = member.desugared,
        )

        /** Merge overloads conservatively: earliest since, and deprecated/removed only when all of them are. */
        private fun merge(levels: List<ApiLevel>): ApiLevel {
            if (levels.size == 1) return levels[0]
            val deprecated = if (levels.all { it.deprecatedIn > 0 }) levels.minOf { it.deprecatedIn } else 0
            val removed = if (levels.all { it.removedIn > 0 }) levels.minOf { it.removedIn } else 0
            val earliest = levels.minBy { it.since }
            return ApiLevel(earliest.since, deprecated, removed, levels.any { it.desugared })
        }

        /** The parameter count of a `name(desc)ret` key whose name is [nameLength] chars long. */
        internal fun arityOf(key: String, nameLength: Int): Int {
            var i = nameLength + 1
            var count = 0
            while (i < key.length && key[i] != ')') {
                while (key[i] == '[') i++
                i = if (key[i] == 'L') key.indexOf(';', i) + 1 else i + 1
                count++
            }
            return count
        }

        /** Decode a table produced by [write]. */
        fun read(input: InputStream): AndroidApiDatabase {
            val bytes = GZIPInputStream(input).readBytes()
            val data = DataInputStream(ByteArrayInputStream(bytes))
            require(data.readInt() == MAGIC) { "not an API-level table" }
            val version = data.readUnsignedShort()
            require(version == VERSION) { "unsupported API-level table version $version" }
            val level = data.readUnsignedShort()
            val count = data.readInt()
            val directory = HashMap<String, Int>(count * 2)
            var offset = 2 * 4 + 2 * 2
            repeat(count) {
                val nameLength = data.readUnsignedShort()
                val nameBytes = ByteArray(nameLength).also { data.readFully(it) }
                val blockLength = data.readInt()
                directory[canonical(String(nameBytes, Charsets.UTF_8))] = offset
                data.skipBytes(blockLength)
                offset += 2 + nameLength + 4 + blockLength
            }
            return AndroidApiDatabase(level, bytes, directory)
        }

        private fun readClass(data: DataInputStream): ClassRecord {
            val name = data.readUTF()
            data.readInt() // block length
            val since = data.readUnsignedShort()
            val deprecated = data.readUnsignedShort()
            val removed = data.readUnsignedShort()
            val supers = List(data.readUnsignedShort()) { data.readUTF() }
            val methods = List(data.readInt()) { readMember(data) }
            val fields = List(data.readInt()) { readMember(data) }
            return ClassRecord(name, since, deprecated, removed, supers, methods, fields)
        }

        private fun readMember(data: DataInputStream): MemberRecord {
            val key = data.readUTF()
            val since = data.readUnsignedShort()
            val deprecated = data.readUnsignedShort()
            val removed = data.readUnsignedShort()
            val flags = data.readUnsignedByte()
            return MemberRecord(key, since, deprecated, removed, desugared = flags and 1 != 0)
        }

        /** Encode [classes] (the platform at [platformLevel]) as a gzip-compressed table. */
        fun write(platformLevel: Int, classes: List<ClassRecord>): ByteArray {
            val raw = ByteArrayOutputStream()
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeShort(VERSION)
                out.writeShort(platformLevel)
                out.writeInt(classes.size)
                for (cls in classes.sortedBy { it.internalName }) {
                    val block = ByteArrayOutputStream()
                    DataOutputStream(block).use { b ->
                        b.writeShort(cls.since)
                        b.writeShort(cls.deprecatedIn)
                        b.writeShort(cls.removedIn)
                        b.writeShort(cls.supers.size)
                        cls.supers.forEach(b::writeUTF)
                        b.writeInt(cls.methods.size)
                        cls.methods.forEach { writeMember(b, it) }
                        b.writeInt(cls.fields.size)
                        cls.fields.forEach { writeMember(b, it) }
                    }
                    out.writeUTF(cls.internalName)
                    out.writeInt(block.size())
                    block.writeTo(out)
                }
            }
            val zipped = ByteArrayOutputStream()
            GZIPOutputStream(zipped).use { it.write(raw.toByteArray()) }
            return zipped.toByteArray()
        }

        private fun writeMember(out: DataOutputStream, m: MemberRecord) {
            out.writeUTF(m.key)
            out.writeShort(m.since)
            out.writeShort(m.deprecatedIn)
            out.writeShort(m.removedIn)
            out.writeByte(if (m.desugared) 1 else 0)
        }
    }
}
