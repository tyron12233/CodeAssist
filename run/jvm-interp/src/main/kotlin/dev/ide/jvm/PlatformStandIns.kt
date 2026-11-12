package dev.ide.jvm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode

/**
 * Interpreted stand-ins for platform classes a host's runtime may lack (see [Vm.installPlatformStandIns]).
 * `Record` and `MatchException` have exactly the shape compiled code links against and nothing else; the
 * larger ones are real implementations written as source ([sourced]).
 */
internal object PlatformStandIns {

    /** Each stand-in by the platform class it replaces, with every class that has to be defined for it (its
     *  nested classes included): the two written in bytecode here, then the ones written as source. */
    val ALL: List<Pair<String, () -> List<ClassNode>>> by lazy {
        listOf<Pair<String, () -> List<ClassNode>>>(
            "java/lang/Record" to { listOf(record()) },
            "java/lang/MatchException" to { listOf(matchException()) },
        ) + sourced()
    }

    /** Where the stand-ins written as source are compiled (`dev.ide.jvm.standin.java.lang.ScopedValue`), and the
     *  package that prefix stands for once the VM defines them. */
    const val SOURCE_PREFIX = "dev/ide/jvm/standin/"

    /** A platform internal name for a stand-in's compiled one, and any other name unchanged. */
    fun platformName(internalName: String): String =
        if (internalName.startsWith(SOURCE_PREFIX + "java/")) internalName.removePrefix(SOURCE_PREFIX) else internalName

    private object ToPlatformNames : Remapper() {
        override fun map(internalName: String): String = platformName(internalName)
    }

    /**
     * The stand-ins written as Java source (`ScopedValue`, `StructuredTaskScope`): real implementations, shipped
     * as class-file resources (`standin-classes/…​.bin`, since a jar's `.class` entries are dex on Android) and
     * renamed into the platform package here. Grouped by top-level class.
     */
    private fun sourced(): List<Pair<String, () -> List<ClassNode>>> =
        compiledNames().groupBy { platformName(it).substringBefore('$') }.map { (top, members) ->
            top to { members.mapNotNull { name -> compiledBytes(name)?.let(::renamed) } }
        }

    /** The compiled internal names of every source stand-in class (`dev/ide/jvm/standin/java/lang/ScopedValue$Carrier`). */
    fun compiledNames(): List<String> =
        PlatformStandIns::class.java.getResourceAsStream("/standin-classes/index.txt")
            ?.use { it.readBytes().decodeToString().lines().filter { l -> l.isNotBlank() } }.orEmpty()

    /** The class file of the source stand-in compiled as [compiledName], as compiled. */
    fun compiledBytes(compiledName: String): ByteArray? =
        PlatformStandIns::class.java.getResourceAsStream("/standin-classes/$compiledName.bin")?.use { it.readBytes() }

    /** A source stand-in's class file with every reference to a stand-in moved into the platform package. */
    fun renamedBytes(bytes: ByteArray): ByteArray {
        val cw = ClassWriter(0)
        ClassReader(bytes).accept(ClassRemapper(cw, ToPlatformNames), 0)
        return cw.toByteArray()
    }

    private fun renamed(bytes: ByteArray): ClassNode = ClassNode().also { ClassReader(renamedBytes(bytes)).accept(it, 0) }

    /** `public abstract class Record { protected Record() {} }` with `equals`/`hashCode`/`toString` abstract. */
    private fun record(): ClassNode {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER, "java/lang/Record", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        for ((name, desc) in listOf("equals" to "(Ljava/lang/Object;)Z", "hashCode" to "()I", "toString" to "()Ljava/lang/String;")) {
            cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd()
        }
        cw.visitEnd()
        return read(cw)
    }

    /** `public final class MatchException extends RuntimeException { MatchException(String, Throwable) }`. */
    private fun matchException(): ClassNode {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, "java/lang/MatchException", null, "java/lang/RuntimeException", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return read(cw)
    }

    private fun read(cw: ClassWriter): ClassNode = ClassNode().also { ClassReader(cw.toByteArray()).accept(it, 0) }
}
