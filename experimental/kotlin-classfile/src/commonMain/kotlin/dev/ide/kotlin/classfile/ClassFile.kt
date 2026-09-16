package dev.ide.kotlin.classfile

/**
 * What `@kotlin.Metadata` carries on a class file.
 *
 * [kind] is what the class IS to Kotlin: 1 a class, 2 a file facade, 3 a synthetic class, 4 a multi-file
 * facade part, 5 a multi-file facade. Only 1 and 2 hold declarations worth reading.
 */
class KotlinMetadataAnnotation(
    val kind: Int,
    val metadataVersion: IntArray,
    val data1: Array<String>,
    val data2: Array<String>,
    val extraString: String?,
    val packageName: String?,
)

/**
 * A `.class` file, read far enough to answer what a Kotlin index needs.
 *
 * This replaces the ASM surface `:lang-kotlin-index` uses, which is narrower than ASM's reputation suggests:
 * a `ClassReader` with `SKIP_CODE`/`SKIP_FRAMES`/`SKIP_DEBUG` and four visitors. Method bodies are exactly
 * what an index does not read, so the expensive half of a class file is skipped rather than parsed.
 *
 * Nothing is validated beyond what is needed to walk the structure. A classpath contains jars built by
 * anything, and an index that refuses a file it could have read mostly is worse than one that reads what it
 * can: callers get null rather than an exception.
 */
class ClassFile private constructor(
    val thisClass: String,
    val superClass: String?,
    val interfaces: List<String>,
    val accessFlags: Int,
    val metadata: KotlinMetadataAnnotation?,
) {

    /** Was this class produced by the Kotlin compiler? */
    val isKotlin: Boolean get() = metadata != null

    companion object {
        private const val MAGIC = 0xCAFEBABE.toInt()
        private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

        /** Read [bytes], or null when they are not a class file this reader understands. */
        fun read(bytes: ByteArray): ClassFile? = runCatching { Reader(bytes).read() }.getOrNull()
    }

    /**
     * The walk itself.
     *
     * A class file is a constant pool followed by fixed structures, none of which can be located without
     * having read everything before it, so this is a single forward pass by necessity rather than by choice.
     */
    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        private fun u1(): Int = bytes[at++].toInt() and 0xFF
        private fun u2(): Int = (u1() shl 8) or u1()
        private fun u4(): Int = (u2() shl 16) or u2()
        private fun skip(n: Int) {
            at += n
        }

        private lateinit var poolTags: IntArray
        private lateinit var poolStrings: Array<String?>
        private lateinit var poolIndices: IntArray
        private lateinit var poolInts: IntArray

        fun read(): ClassFile? {
            if (u4() != MAGIC) return null
            skip(4) // minor and major version

            readConstantPool()

            val accessFlags = u2()
            val thisClass = className(u2()) ?: return null
            val superIndex = u2()
            val superClass = if (superIndex == 0) null else className(superIndex)
            val interfaces = List(u2()) { className(u2()) }.filterNotNull()

            skipMembers() // fields
            skipMembers() // methods

            return ClassFile(thisClass, superClass, interfaces, accessFlags, readClassAttributes())
        }

        /**
         * The constant pool.
         *
         * Two entries occupy TWO slots — `long` and `double` — a quirk of the format that is easy to miss and
         * misaligns every index after it when missed, which then reads as corrupt names rather than as an
         * off-by-one.
         */
        private fun readConstantPool() {
            val count = u2()
            poolTags = IntArray(count)
            poolStrings = arrayOfNulls(count)
            poolIndices = IntArray(count)
            poolInts = IntArray(count)

            var i = 1
            while (i < count) {
                val tag = u1()
                poolTags[i] = tag
                when (tag) {
                    1 -> { // UTF8
                        val length = u2()
                        poolStrings[i] = decodeModifiedUtf8(at, length)
                        skip(length)
                    }

                    7, 8, 16, 19, 20 -> poolIndices[i] = u2() // Class, String, MethodType, Module, Package
                    15 -> skip(3) // MethodHandle
                    // An `int` constant is read HERE rather than re-derived later: the pool can only be
                    // walked forwards, so a second pass to find one value would have to replay the whole
                    // thing, and `@Metadata`'s `k` and `mv` are ints.
                    3 -> poolInts[i] = u4()
                    4, 9, 10, 11, 12, 17, 18 -> skip(4) // Float, refs, NameAndType, Dynamic
                    5, 6 -> { // Long, Double: two slots
                        skip(8)
                        i++
                    }

                    else -> return // unknown tag: stop rather than walk off into the weeds
                }
                i++
            }
        }

        /**
         * Modified UTF-8, which is not UTF-8.
         *
         * A NUL is two bytes and a supplementary character is two three-byte surrogates rather than one
         * four-byte sequence, so `decodeToString` gets both wrong. Names with either are rare, which is
         * exactly why it would be found late.
         */
        private fun decodeModifiedUtf8(start: Int, length: Int): String {
            val out = StringBuilder(length)
            var i = start
            val end = start + length
            while (i < end) {
                val a = bytes[i].toInt() and 0xFF
                when {
                    a < 0x80 -> {
                        out.append(a.toChar())
                        i++
                    }

                    a and 0xE0 == 0xC0 -> {
                        val b = bytes[i + 1].toInt() and 0x3F
                        out.append((((a and 0x1F) shl 6) or b).toChar())
                        i += 2
                    }

                    else -> {
                        val b = bytes[i + 1].toInt() and 0x3F
                        val c = bytes[i + 2].toInt() and 0x3F
                        out.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar())
                        i += 3
                    }
                }
            }
            return out.toString()
        }

        private fun utf8(index: Int): String? = poolStrings.getOrNull(index)

        private fun className(index: Int): String? =
            if (index == 0 || index >= poolTags.size) null else utf8(poolIndices[index])

        /** Fields and methods share a shape, and an index reads neither's code. */
        private fun skipMembers() {
            repeat(u2()) {
                skip(6) // access flags, name index, descriptor index
                repeat(u2()) {
                    skip(2)
                    skip(u4())
                }
            }
        }

        private fun readClassAttributes(): KotlinMetadataAnnotation? {
            var metadata: KotlinMetadataAnnotation? = null
            repeat(u2()) {
                val name = utf8(u2())
                val length = u4()
                val end = at + length
                if (name == "RuntimeVisibleAnnotations") {
                    metadata = metadata ?: findMetadataAnnotation()
                }
                at = end
            }
            return metadata
        }

        private fun findMetadataAnnotation(): KotlinMetadataAnnotation? {
            repeat(u2()) {
                val descriptor = utf8(u2())
                if (descriptor == METADATA_DESCRIPTOR) return readMetadataAnnotation()
                skipAnnotationValues()
            }
            return null
        }

        private fun readMetadataAnnotation(): KotlinMetadataAnnotation {
            var kind = 1
            var version = intArrayOf()
            var d1 = emptyArray<String>()
            var d2 = emptyArray<String>()
            var extraString: String? = null
            var packageName: String? = null

            repeat(u2()) {
                when (utf8(u2())) {
                    "k" -> kind = intValue()
                    "mv" -> version = intArrayValue()
                    "d1" -> d1 = stringArrayValue()
                    "d2" -> d2 = stringArrayValue()
                    "xs" -> extraString = stringValue()
                    "pn" -> packageName = stringValue()
                    else -> skipValue()
                }
            }
            return KotlinMetadataAnnotation(kind, version, d1, d2, extraString, packageName)
        }

        // --- annotation element values ------------------------------------------------------------------
        // Each is a one-byte tag then a payload whose shape depends on it. Only the four `@Metadata` uses are
        // decoded; the rest are skipped, since an unknown value must not derail the walk.

        private fun intValue(): Int {
            skip(1) // tag 'I'
            return constantInt(u2())
        }

        private fun stringValue(): String? {
            skip(1) // tag 's'
            return utf8(u2())
        }

        private fun intArrayValue(): IntArray {
            skip(1) // tag '['
            return IntArray(u2()) { intValue() }
        }

        private fun stringArrayValue(): Array<String> {
            skip(1) // tag '['
            val values = ArrayList<String>()
            repeat(u2()) { stringValue()?.let(values::add) }
            return values.toTypedArray()
        }

        private fun constantInt(index: Int): Int = poolInts.getOrElse(index) { 0 }

        private fun skipValue() {
            when (u1().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> skip(2)
                'e' -> skip(4)
                '@' -> {
                    skip(2)
                    repeat(u2()) {
                        skip(2)
                        skipValue()
                    }
                }

                '[' -> repeat(u2()) { skipValue() }
            }
        }

        private fun skipAnnotationValues() {
            repeat(u2()) {
                skip(2)
                skipValue()
            }
        }
    }
}
