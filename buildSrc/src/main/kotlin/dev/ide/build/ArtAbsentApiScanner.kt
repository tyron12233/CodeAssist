package dev.ide.build

import java.util.zip.ZipFile

/**
 * Scans `.class` bytes for references to `java.*` types that exist on the JVM but are **absent from
 * Android's ART** at the app's `minSdk` and are **not** backported by core-library desugaring (e.g.
 * `java.lang.StackWalker`, `java.lang.Runtime$Version`, `java.lang.foreign.*`). Such a reference, when it
 * sits in a class-load position you cannot guard, surfaces on-device as an uncatchable
 * [NoClassDefFoundError] and silently disables a whole feature — exactly the `StackWalker` / `Runtime$Version`
 * failures the [RelocateTypesInJar] shims were created for (see `:ide-android`).
 *
 * The scanner classifies each reference by **where** it appears, because the position decides severity:
 *
 *  - [Position.SUPERTYPE] / [Position.STATIC_FIELD] — **load-bearing**: resolved when the class loads /
 *    its `<clinit>` runs, so the failure is *uncatchable* and disables the feature outright. The build
 *    should fail on these. Detecting them is precise (no false positives): a class only lands here if it
 *    literally extends/implements the absent type or declares a `static` field of it.
 *  - [Position.INSTANCE_FIELD] / [Position.METHOD_REF] — **lazily-reached**: resolved only when the field
 *    is accessed / the method is called, so it is at worst catchable and is often a cold path that never
 *    executes on-device (e.g. ecj's `ConstantPool` references `java.lang.constant.*` only for `condy`
 *    emission, which normal compilation never produces). These are reported as *advisory* — worth a human
 *    look, but not a build break, since failing on them would false-positive on working code.
 *
 * Pure and dependency-free (no ASM, no Gradle types) so it is unit-testable in isolation; the Gradle
 * wrapper is [ScanForArtAbsentApis].
 */
object ArtAbsentApiScanner {

    enum class Position {
        /** `extends`/`implements` the absent type — fails at class load. Uncatchable. */
        SUPERTYPE,

        /** A `static` field of the absent type — fails when `<clinit>` runs. Uncatchable. */
        STATIC_FIELD,

        /** A non-static field of the absent type — fails on first field access. Lazily reached. */
        INSTANCE_FIELD,

        /** Referenced only from method descriptors / bodies — fails on first call, if ever. */
        METHOD_REF,
    }

    data class Finding(
        val className: String,
        /** The denylisted internal name (or prefix) that matched, e.g. `java/lang/StackWalker`. */
        val absentType: String,
        val position: Position,
        /** Field name when [position] is a field; null otherwise. */
        val detail: String? = null,
    ) {
        /** Load-bearing positions resolve at class load → uncatchable on-device → should fail the build. */
        val isLoadBearing: Boolean get() = position == Position.SUPERTYPE || position == Position.STATIC_FIELD
    }

    /**
     * The default denylist: `java.*` internal names (or package prefixes ending in `/`) added after API 26
     * or never present on ART, and **not** desugared. Deliberately excludes types that ARE on ART at the
     * floor — notably `java/lang/invoke/MethodHandles$Lookup` (API 26; the bulk of its references are
     * lambda / string-concat `invokedynamic` bootstrap that D8 desugars).
     */
    val DEFAULT_DENYLIST: Set<String> = setOf(
        "java/lang/StackWalker",            // Java 9
        "java/lang/Runtime\$Version",       // Java 9
        "java/lang/IllegalCallerException", // Java 9 (ART API 33)
        "java/lang/ProcessHandle",          // Java 9
        "java/lang/Module",                 // Java 9 modules (also catches ModuleLayer/ModuleDescriptor)
        "java/lang/constant/",              // Java 12 (package prefix)
        "java/lang/foreign/",               // Java 21 panama (package prefix)
        "java/lang/ScopedValue",            // Java 21
        "java/lang/Thread\$Builder",        // Java 21 virtual threads
        "java/util/SequencedCollection",    // Java 21
        "java/util/SequencedMap",           // Java 21
        "java/util/SequencedSet",           // Java 21
        "java/lang/classfile/",             // Java 24 (package prefix)
    )

    /** Scans every `.class` in [jar] and returns all findings. */
    fun scanJar(jar: java.io.File, denylist: Set<String> = DEFAULT_DENYLIST): List<Finding> {
        val out = ArrayList<Finding>()
        ZipFile(jar).use { zip ->
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                out += scanClass(bytes, denylist)
            }
        }
        return out
    }

    /** Scans a single class file. Returns at most one finding per matched absent type (strongest position). */
    fun scanClass(bytes: ByteArray, denylist: Set<String> = DEFAULT_DENYLIST): List<Finding> {
        val cf = try {
            parse(bytes)
        } catch (_: Exception) {
            return emptyList() // malformed / unsupported entry — not our concern
        } ?: return emptyList()

        // Cheap reject: if no denylisted name appears in ANY utf8 constant, there is nothing to find.
        if (cf.utf8.none { u -> denylist.any { u.contains(it) } }) return emptyList()

        // For each denylisted type present, record the STRONGEST position it appears in.
        val strongest = LinkedHashMap<String, Finding>()
        fun consider(absent: String, pos: Position, detail: String?) {
            val cur = strongest[absent]
            if (cur == null || pos.ordinal < cur.position.ordinal) {
                strongest[absent] = Finding(cf.thisName, absent, pos, detail)
            }
        }

        for (absent in denylist) {
            // SUPERTYPE
            if (cf.superName.contains(absent) || cf.interfaces.any { it.contains(absent) }) {
                consider(absent, Position.SUPERTYPE, null)
            }
            // FIELDS (static vs instance)
            for (f in cf.fields) {
                if (f.descriptor.contains(absent)) {
                    consider(absent, if (f.isStatic) Position.STATIC_FIELD else Position.INSTANCE_FIELD, f.name)
                }
            }
            // Anything else (method descriptors, bodies via Class/NameAndType constants) → METHOD_REF.
            if (absent !in strongest && cf.utf8.any { it.contains(absent) }) {
                consider(absent, Position.METHOD_REF, null)
            }
        }
        return strongest.values.toList()
    }

    // --- minimal class-file model -----------------------------------------------------------------

    private class FieldInfo(val isStatic: Boolean, val name: String, val descriptor: String)
    private class ClassFile(
        val thisName: String,
        val superName: String,
        val interfaces: List<String>,
        val fields: List<FieldInfo>,
        val utf8: List<String>,
    )

    private const val ACC_STATIC = 0x0008

    /** Parses the header, constant pool and field table (enough to locate references by position). */
    private fun parse(b: ByteArray): ClassFile? {
        fun u2(o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
        fun u4(o: Int) = (u2(o).toLong() shl 16) or u2(o + 2).toLong()
        if (b.size < 10 || u2(0) != 0xCAFE || u2(2) != 0xBABE) return null

        val cpCount = u2(8)
        // index -> entry. For Utf8: the String. For Class: the name-index (Int boxed). Others: null.
        val utf8ByIndex = arrayOfNulls<String>(cpCount)
        val classNameIndex = IntArray(cpCount) { -1 }
        var i = 10
        var idx = 1
        while (idx < cpCount) {
            when (b[i].toInt() and 0xff) {
                1 -> { // Utf8
                    val len = u2(i + 1)
                    utf8ByIndex[idx] = String(b, i + 3, len, Charsets.UTF_8)
                    i += 3 + len; idx += 1
                }
                7 -> { classNameIndex[idx] = u2(i + 1); i += 3; idx += 1 } // Class -> name index
                8, 16, 19, 20 -> { i += 3; idx += 1 }
                15 -> { i += 4; idx += 1 }
                3, 4, 9, 10, 11, 12, 17, 18 -> { i += 5; idx += 1 }
                5, 6 -> { i += 9; idx += 2 } // Long/Double take two slots
                else -> return null
            }
        }
        fun className(classIdx: Int): String =
            if (classIdx in 1 until cpCount && classNameIndex[classIdx] >= 0)
                utf8ByIndex[classNameIndex[classIdx]] ?: "" else ""

        // access_flags(2) this_class(2) super_class(2)
        val thisName = className(u2(i + 2))
        val superName = className(u2(i + 4))
        i += 6
        val ifaceCount = u2(i); i += 2
        val interfaces = ArrayList<String>(ifaceCount)
        repeat(ifaceCount) { interfaces += className(u2(i)); i += 2 }

        // fields
        val fieldCount = u2(i); i += 2
        val fields = ArrayList<FieldInfo>(fieldCount)
        repeat(fieldCount) {
            val acc = u2(i); val nameIdx = u2(i + 2); val descIdx = u2(i + 4); i += 6
            fields += FieldInfo(acc and ACC_STATIC != 0, utf8ByIndex[nameIdx] ?: "", utf8ByIndex[descIdx] ?: "")
            val attrs = u2(i); i += 2
            repeat(attrs) { i += 6 + u4(i + 2).toInt() } // skip each attribute (u2 name, u4 len, len bytes)
        }

        val utf8 = utf8ByIndex.filterNotNull()
        return ClassFile(thisName, superName, interfaces, fields, utf8)
    }
}
