package dev.ide.lang.java.index

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The ASM-based bytecode reader for the Java binary indexes — the replacement for ecj's `ClassFileReader`
 * (dropping the JDT dependency from indexing). Reads only the class-level shape (access flags + method/field
 * names & JVM descriptors), skipping code/frames/debug, so it is cheap over a whole classpath. The descriptors
 * it emits (`(Ljava/lang/String;)I`, `Ljava/util/List;`) are the erased JVM descriptors — byte-identical to
 * what ecj's `getMethodDescriptor()` / field `getTypeName()` produced, so the `MemberValue` segments are
 * unchanged and the JDT compile name-environment (still an index consumer) keeps resolving.
 */
object JavaBytecode {

    data class Member(val name: String, val descriptor: String)
    class ClassInfo(val access: Int, val internalName: String, val methods: List<Member>, val fields: List<Member>)

    fun read(bytes: ByteArray): ClassInfo? = runCatching {
        val methods = ArrayList<Member>()
        val fields = ArrayList<Member>()
        var access = 0
        var internalName = ""
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, acc: Int, name: String?, sig: String?, superName: String?, ifaces: Array<out String>?,
                ) {
                    access = acc
                    internalName = name ?: ""
                }

                override fun visitMethod(
                    acc: Int, name: String?, desc: String?, sig: String?, exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != null && desc != null) methods += Member(name, desc)
                    return null
                }

                override fun visitField(
                    acc: Int, name: String?, desc: String?, sig: String?, value: Any?,
                ): FieldVisitor? {
                    if (name != null && desc != null) fields += Member(name, desc)
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        ClassInfo(access, internalName, methods, fields)
    }.getOrNull()

    fun isPublic(access: Int): Boolean = (access and Opcodes.ACC_PUBLIC) != 0
}
