package dev.ide.android.support.metadata.gen

import dev.ide.android.support.apilevels.AndroidApiDatabase
import dev.ide.android.support.apilevels.AndroidApiDatabase.ClassRecord
import dev.ide.android.support.apilevels.AndroidApiDatabase.MemberRecord
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Generates the bundled JDK API-level table (`jdk-api-levels.bin`, the same format as the Android table) from a
 * JDK's `lib/ct.sym`, the signature data `javac --release` compiles against: one directory per run of releases
 * (`9ABC/java.base/java/util/List.sig` holds `List` as Java 9 to 12 saw it), each file a stripped class file.
 *
 * A member's `since` is the first release where a call to it COMPILES, which is earlier than the release that
 * declared it when a supertype already had the same name and parameters: Java 9's covariant `ByteBuffer.flip()`
 * overrides Java 8's `Buffer.flip()`, so `buffer.flip()` is fine at 8 although this JDK resolves it to the
 * newer override. Releases before 8 are not in `ct.sym`, so 8 means "8 or earlier".
 *
 * Usage:
 * ```
 * ./gradlew :android-sdk-metadata:generateJdkApiLevels --args \
 *   "$JAVA_HOME/lib/ct.sym android/android-support/src/main/resources/jdk-api-levels.bin"
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: <ct.sym> <output.bin>")
        return
    }
    val out = Path.of(args[1])
    val table = JdkApiLevelsExtractor.extract(Path.of(args[0]))
    val bytes = AndroidApiDatabase.write(table.latest, table.classes)
    out.parent?.let { Files.createDirectories(it) }
    Files.write(out, bytes)
    val members = table.classes.sumOf { it.methods.size + it.fields.size }
    println("Wrote ${out.toAbsolutePath()}: Java ${table.latest}, ${table.classes.size} classes, $members members, ${bytes.size} bytes")
}

internal object JdkApiLevelsExtractor {

    class Table(val latest: Int, val classes: List<ClassRecord>)

    /** One class as one release saw it. Methods are keyed `name(desc)ret`, fields by name. */
    private class Sig(
        val public: Boolean,
        val isInterface: Boolean,
        val deprecated: Boolean,
        val supers: List<String>,
        val methods: Map<String, Member>,
        val fields: Map<String, Member>,
    )

    private class Member(val deprecated: Boolean, val isStatic: Boolean)

    fun extract(ctSym: Path): Table {
        // release -> internal class name -> its signature in that release
        val releases = sortedMapOf<Int, MutableMap<String, Sig>>()
        ZipFile(ctSym.toFile()).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (entry.isDirectory || !name.endsWith(".sig") || name.endsWith("module-info.sig")) continue
                val parts = name.split('/', limit = 3)
                if (parts.size < 3) continue
                val levels = parts[0].map(::releaseOf)
                if (levels.any { it < 0 }) continue
                val className = parts[2].removeSuffix(".sig")
                val sig = zip.getInputStream(entry).use { read(it.readBytes()) }
                for (level in levels) releases.getOrPut(level) { HashMap() }[className] = sig
            }
        }
        require(releases.isNotEmpty()) { "no signatures in $ctSym" }
        val order = releases.keys.toList()
        val latest = order.last()

        // Can `name(params)` be called on [cls] in [release]: declared there, or inherited from a supertype?
        // Constructors are never inherited, nor an interface's static methods.
        fun callable(cls: String, release: Int, nameAndParams: String, field: Boolean, seen: HashSet<String> = HashSet()): Boolean {
            if (!seen.add(cls)) return false
            val sig = releases[release]?.get(cls) ?: return false
            val declared = if (field) sig.fields.containsKey(nameAndParams) else sig.methods.keys.any { it.startsWith(nameAndParams) }
            if (declared) return true
            if (!field && nameAndParams.startsWith("<init>(")) return false
            return sig.supers.any { s ->
                val superSig = releases[release]?.get(s)
                if (!field && superSig != null && superSig.isInterface) {
                    superSig.methods.any { (k, m) -> !m.isStatic && k.startsWith(nameAndParams) } || superSig.supers.any { callable(it, release, nameAndParams, false, seen) }
                } else {
                    callable(s, release, nameAndParams, field, seen)
                }
            }
        }

        val classNames = releases.values.flatMapTo(sortedSetOf()) { r -> r.filterValues { it.public }.keys }
        val classes = classNames.map { cls ->
            val present = order.filter { releases[it]!![cls]?.public == true }
            val since = present.first()
            val last = present.last()
            val removed = if (last == latest) 0 else order.first { it > last }
            val newest = releases[last]!![cls]!!
            val classDeprecated = present.firstOrNull { releases[it]!![cls]!!.deprecated } ?: 0

            fun member(key: String, field: Boolean): MemberRecord {
                val nameAndParams = if (field) key else key.substring(0, key.indexOf(')') + 1)
                val declaredIn = present.filter { r -> releases[r]!![cls]!!.let { if (field) key in it.fields else key in it.methods } }
                val callableIn = present.filter { r -> callable(cls, r, nameAndParams, field) }
                val first = (callableIn.firstOrNull() ?: declaredIn.first())
                val lastSeen = maxOf(declaredIn.last(), callableIn.lastOrNull() ?: 0)
                val memberRemoved = if (lastSeen == latest || lastSeen == last) 0 else order.first { it > lastSeen }
                val deprecated = declaredIn.firstOrNull { r ->
                    releases[r]!![cls]!!.let { (if (field) it.fields[key] else it.methods[key])!!.deprecated }
                } ?: 0
                return MemberRecord(
                    key,
                    since = if (first == since) 0 else first,
                    deprecatedIn = if (deprecated == classDeprecated) 0 else deprecated,
                    removedIn = memberRemoved,
                )
            }

            val methodKeys = present.flatMapTo(sortedSetOf()) { releases[it]!![cls]!!.methods.keys }
            val fieldKeys = present.flatMapTo(sortedSetOf()) { releases[it]!![cls]!!.fields.keys }
            ClassRecord(
                internalName = cls,
                since = since,
                deprecatedIn = classDeprecated,
                removedIn = removed,
                supers = newest.supers,
                methods = methodKeys.map { member(it, field = false) },
                fields = fieldKeys.map { member(it, field = true) },
            )
        }
        return Table(latest, classes)
    }

    /** `8`, `9`, then `A` = 10, `B` = 11, ...; -1 for anything else. */
    internal fun releaseOf(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> 10 + (c - 'A')
        else -> -1
    }

    private const val VISIBLE = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED

    private fun read(bytes: ByteArray): Sig {
        // The stubs carry the newest release's class-file version, which this ASM may not know yet; only the
        // signatures are read, so present them as Java 8 class files.
        bytes[6] = 0
        bytes[7] = 52
        var access = 0
        val supers = ArrayList<String>()
        val methods = HashMap<String, Member>()
        val fields = HashMap<String, Member>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, acc: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                access = acc
                superName?.let(supers::add)
                interfaces?.let { supers.addAll(it) }
            }

            override fun visitMethod(acc: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (acc and VISIBLE != 0 && acc and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 && name != "<clinit>") {
                    methods[name + descriptor] = Member(acc and Opcodes.ACC_DEPRECATED != 0, acc and Opcodes.ACC_STATIC != 0)
                }
                return null
            }

            override fun visitField(acc: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                if (acc and VISIBLE != 0 && acc and Opcodes.ACC_SYNTHETIC == 0) {
                    fields[name] = Member(acc and Opcodes.ACC_DEPRECATED != 0, acc and Opcodes.ACC_STATIC != 0)
                }
                return null
            }
        }, ClassReader.SKIP_CODE)
        return Sig(
            public = access and Opcodes.ACC_PUBLIC != 0,
            isInterface = access and Opcodes.ACC_INTERFACE != 0,
            deprecated = access and Opcodes.ACC_DEPRECATED != 0,
            supers = supers,
            methods = methods,
            fields = fields,
        )
    }
}
