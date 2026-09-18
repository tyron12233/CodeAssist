package dev.ide.lang.kotlin.symbols

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal actual fun scanOptIn(classBytes: ByteArray): KotlinOptIn.OptInScan? = AsmOptIn.scan(classBytes)

/** The JVM half of [KotlinOptIn]: ASM, which reads annotation values. */
private object AsmOptIn {

    private const val REQUIRES_OPT_IN_DESC = "Lkotlin/RequiresOptIn;"

    /**
     * ASM-scan [classBytes] for the opt-in mechanism's annotations — the authoritative source, since a
     * `@RequiresOptIn` marker is `@Retention(BINARY)` and so lands in `RuntimeInvisibleAnnotations` (both
     * visible and invisible are read). This is how a library declaration's experimental markers are recovered
     * on demand (no `@Metadata` decode needed; the annotations aren't reliably in the metadata blob). Null when
     * the bytes can't be read.
     */
    fun scan(classBytes: ByteArray): KotlinOptIn.OptInScan? {
        val reader = runCatching { ClassReader(classBytes) }.getOrNull() ?: return null
        val classAnnos = ArrayList<String>()
        var markerLevel: String? = null
        var isMarker = false
        // Per method NAME, the sets of annotation FQNs seen on each method of that name (one set per overload),
        // intersected at the end so a name is only attributed a marker when EVERY overload carries it.
        val perMethod = HashMap<String, MutableList<MutableSet<String>>>()
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                descriptor?.let { classAnnos += fqnOfDesc(it) }
                if (descriptor == REQUIRES_OPT_IN_DESC) {
                    isMarker = true
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visitEnum(name: String?, desc: String?, value: String?) {
                            if (name == "level" && value != null) markerLevel = value
                        }
                    }
                }
                return null
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, ex: Array<out String>?): MethodVisitor {
                // Key by the demangled Kotlin name so a target looked up by its source name (`Text`) matches a
                // value-class-mangled JVM method (`Text-<hash>`); skip compiler synthetics (`foo$default`,
                // `access$…`) whose annotations would dilute the real member's when intersected.
                if ('$' in name) return NULL_METHOD_VISITOR
                val here = HashSet<String>()
                perMethod.getOrPut(name.substringBefore('-')) { ArrayList() }.add(here)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
                        desc?.let { here += fqnOfDesc(it) }
                        return null
                    }
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val methodAnnos = perMethod.mapValues { (_, overloads) ->
            overloads.reduce { acc, s -> acc.apply { retainAll(s) } }.toList()
        }.filterValues { it.isNotEmpty() }
        return KotlinOptIn.OptInScan(
            classAnnotations = classAnnos,
            methodAnnotations = methodAnnos,
            requiresOptInLevel = if (isMarker) (markerLevel ?: "ERROR") else null,
        )
    }

    private val NULL_METHOD_VISITOR = object : MethodVisitor(Opcodes.ASM9) {}

    /** FQN of an annotation type descriptor (`Lcom/foo/Bar;` → `com.foo.Bar`). */
    private fun fqnOfDesc(desc: String): String =
        if (desc.startsWith("L") && desc.endsWith(";")) desc.substring(1, desc.length - 1).replace('/', '.')
        else desc

    /** One-shot guard so a decode failure (e.g. `kotlin-metadata-jvm` missing on ART) is logged once, not per class. */
    private val loggedDecodeFailure = java.util.concurrent.atomic.AtomicBoolean(false)

}
