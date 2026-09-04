package dev.ide.ksp

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Reads the method names a compiled class declares, straight from its bytes — no class loading, the same
 * way the rest of the IDE scans a classpath (`android-support`'s custom-View scan, `lang-java`'s index
 * bridge). Used by [KspProcessorCatalog.missingRuntimeMembers] to tell a runtime that carries a type from
 * one that carries the *revision* of that type the bundled processor's generated code needs: an annotation's
 * elements are methods, so "does `AggregatedRoot` declare `rootComponentPackage()`?" is this question.
 */
internal object ClassFileMembers {

    /**
     * The names of the methods [classFile] declares (annotation elements included), or **null** when the
     * bytes can't be read — a truncated jar entry, or a class file newer than the bundled ASM.
     *
     * Null is deliberately distinct from an empty set: the caller uses this to decide whether to FAIL a
     * build, so "I couldn't tell" must not read as "the member is missing".
     */
    fun methodNames(classFile: ByteArray): Set<String>? = runCatching {
        val names = LinkedHashSet<String>()
        ClassReader(classFile).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    names += name
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        names
    }.getOrNull()
}
