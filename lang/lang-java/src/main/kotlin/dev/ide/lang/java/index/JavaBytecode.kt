package dev.ide.lang.java.index

import dev.ide.kotlin.classfile.ClassFile
import org.objectweb.asm.Opcodes

/**
 * The bytecode reader for the Java binary indexes — the replacement for ecj's `ClassFileReader` (dropping the
 * JDT dependency from indexing). Reads only the class-level shape (access flags + method/field names & JVM
 * descriptors), never method bodies, so it is cheap over a whole classpath. The descriptors it emits
 * (`(Ljava/lang/String;)I`, `Ljava/util/List;`) are the erased JVM descriptors — byte-identical to what ecj's
 * `getMethodDescriptor()` / field `getTypeName()` produced, so the `MemberValue` segments are unchanged and
 * the JDT compile name-environment (still an index consumer) keeps resolving.
 *
 * Reads through `:kotlin-classfile` rather than ASM, so the ONE shared parse per class is shared with the
 * Kotlin binary indexes again, which now decode through the same reader.
 */
object JavaBytecode {

    data class Member(val name: String, val descriptor: String)
    class ClassInfo(val access: Int, val internalName: String, val methods: List<Member>, val fields: List<Member>)

    fun read(bytes: ByteArray): ClassInfo? = ClassFile.read(bytes)?.let { read(it) }

    /** [read] over an already-parsed [classFile] — the index build shares ONE per class across the Java AND
     *  Kotlin binary index families (keyed on [dev.ide.index.IndexInput.CLASS_FILE]) rather than parsing per
     *  index, so every `android.jar` class is read once, not ≈6 times. */
    fun read(classFile: ClassFile): ClassInfo = ClassInfo(
        access = classFile.accessFlags,
        internalName = classFile.thisClass,
        methods = classFile.methods.map { Member(it.name, it.descriptor) },
        fields = classFile.fields.map { Member(it.name, it.descriptor) },
    )

    fun isPublic(access: Int): Boolean = (access and Opcodes.ACC_PUBLIC) != 0

    /** The declaration kind from the class access flags (annotations also carry `ACC_INTERFACE`, so test it
     *  first). Same string vocabulary as [JavaSourceIndexer.DeclKind]`.name.lowercase()` for source types. */
    fun kindOf(access: Int): String = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
        access and Opcodes.ACC_INTERFACE != 0 -> "interface"
        access and Opcodes.ACC_ENUM != 0 -> "enum"
        else -> "class"
    }
}
