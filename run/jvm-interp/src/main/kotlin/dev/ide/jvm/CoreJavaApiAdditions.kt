package dev.ide.jvm

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The Java 21 API the VM supplies at run time, as class files for the platform a program COMPILES against.
 *
 * On a phone a console program is compiled against `android.jar`'s `java.*`, which has no `Thread.ofVirtual`,
 * no `Executors.newVirtualThreadPerTaskExecutor`, no `ScopedValue` and no `StructuredTaskScope`: code using
 * them failed to compile, however well the VM could have run it ([EmulatedThreads], [PlatformStandIns]). This
 * is the other half. A host that derives a core-Java platform jar passes each entry through [rewrite], which
 * adds the missing members to `Thread` and `Executors`, and appends [additions], the classes that are missing
 * outright. Only for a module the VM runs: an Android app is compiled against the real platform, where these
 * do not exist.
 */
object CoreJavaApiAdditions {

    /** Part of the derived jar's cache key: change it with anything here that changes the jar. */
    const val VERSION = "1"

    private const val THREAD = "java/lang/Thread"
    private const val BUILDER = "java/lang/Thread\$Builder"
    private const val OF_VIRTUAL = "java/lang/Thread\$Builder\$OfVirtual"
    private const val OF_PLATFORM = "java/lang/Thread\$Builder\$OfPlatform"
    private const val EXECUTORS = "java/util/concurrent/Executors"

    private class Stub(val access: Int, val name: String, val descriptor: String)

    private const val PUBLIC_STATIC = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
    private const val PUBLIC_FINAL = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL

    private val MEMBERS: Map<String, List<Stub>> = mapOf(
        THREAD to listOf(
            Stub(PUBLIC_STATIC, "ofVirtual", "()L$OF_VIRTUAL;"),
            Stub(PUBLIC_STATIC, "ofPlatform", "()L$OF_PLATFORM;"),
            Stub(PUBLIC_STATIC, "startVirtualThread", "(Ljava/lang/Runnable;)L$THREAD;"),
            Stub(PUBLIC_FINAL, "isVirtual", "()Z"),
            Stub(PUBLIC_FINAL, "threadId", "()J"),
        ),
        EXECUTORS to listOf(
            Stub(PUBLIC_STATIC, "newVirtualThreadPerTaskExecutor", "()Ljava/util/concurrent/ExecutorService;"),
            Stub(PUBLIC_STATIC, "newThreadPerTaskExecutor", "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"),
        ),
    )

    /** The platform jar entry [entryName] with the members this runtime adds, or [bytes] untouched. */
    fun rewrite(entryName: String, bytes: ByteArray): ByteArray {
        val stubs = MEMBERS[entryName.removeSuffix(".class")] ?: return bytes
        return runCatching {
            val reader = ClassReader(bytes)
            val writer = ClassWriter(reader, 0)
            val present = HashSet<String>()
            val nested = HashSet<String>()
            reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    present += name + descriptor
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }

                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    nested += name
                    super.visitInnerClass(name, outerName, innerName, access)
                }

                override fun visitEnd() {
                    if (reader.className == THREAD) innerClasses(this, nested)
                    for (stub in stubs) {
                        if (stub.name + stub.descriptor !in present) throwingStub(this, stub.access, stub.name, stub.descriptor)
                    }
                    super.visitEnd()
                }
            }, 0)
            writer.toByteArray()
        }.getOrDefault(bytes)
    }

    /** The class files to append, by jar entry name, leaving out any the platform jar already [has]. */
    fun additions(has: (entryName: String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in threadBuilders()) out["$name.class"] = bytes
        for (compiled in PlatformStandIns.compiledNames()) {
            val bytes = PlatformStandIns.compiledBytes(compiled) ?: continue
            out[PlatformStandIns.platformName(compiled) + ".class"] = PlatformStandIns.renamedBytes(bytes)
        }
        // A platform that has a class keeps its own, nested classes and all.
        return out.filterKeys { entry -> !has(entry.removeSuffix(".class").substringBefore('$') + ".class") || entry.startsWith(BUILDER) && !has(entry) }
    }

    /**
     * A class file that refers to the source stand-ins by the names they are compiled under
     * (`dev.ide.jvm.standin.java.lang.ScopedValue`), with those references moved to the platform names a real
     * program has. For running such a class the way a program compiled against the additions runs.
     */
    fun toPlatformNames(classFile: ByteArray): ByteArray = PlatformStandIns.renamedBytes(classFile)

    private const val NESTED_INTERFACE = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT

    private fun innerClasses(cv: ClassVisitor, already: Set<String> = emptySet()) {
        if (BUILDER !in already) cv.visitInnerClass(BUILDER, THREAD, "Builder", NESTED_INTERFACE)
        if (OF_VIRTUAL !in already) cv.visitInnerClass(OF_VIRTUAL, BUILDER, "OfVirtual", NESTED_INTERFACE)
        if (OF_PLATFORM !in already) cv.visitInnerClass(OF_PLATFORM, BUILDER, "OfPlatform", NESTED_INTERFACE)
    }

    /** `Thread.Builder` and its two sub-interfaces, each returning itself from the configuring methods. */
    private fun threadBuilders(): Map<String, ByteArray> {
        fun configuring(self: String) = listOf(
            "name" to "(Ljava/lang/String;)L$self;",
            "name" to "(Ljava/lang/String;J)L$self;",
            "inheritInheritableThreadLocals" to "(Z)L$self;",
            "uncaughtExceptionHandler" to "(Ljava/lang/Thread\$UncaughtExceptionHandler;)L$self;",
        )
        fun iface(name: String, superInterface: String?, methods: List<Pair<String, String>>): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", superInterface?.let { arrayOf(it) })
            innerClasses(cw)
            for ((method, descriptor) in methods) cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, method, descriptor, null, null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }
        return linkedMapOf(
            BUILDER to iface(
                BUILDER, null,
                configuring(BUILDER) + listOf(
                    "unstarted" to "(Ljava/lang/Runnable;)L$THREAD;",
                    "start" to "(Ljava/lang/Runnable;)L$THREAD;",
                    "factory" to "()Ljava/util/concurrent/ThreadFactory;",
                ),
            ),
            OF_VIRTUAL to iface(OF_VIRTUAL, BUILDER, configuring(OF_VIRTUAL)),
            OF_PLATFORM to iface(
                OF_PLATFORM, BUILDER,
                configuring(OF_PLATFORM) + listOf(
                    "group" to "(Ljava/lang/ThreadGroup;)L$OF_PLATFORM;",
                    "daemon" to "()L$OF_PLATFORM;",
                    "daemon" to "(Z)L$OF_PLATFORM;",
                    "priority" to "(I)L$OF_PLATFORM;",
                    "stackSize" to "(J)L$OF_PLATFORM;",
                ),
            ),
        )
    }

    /** A method that is only ever compiled against, with the body every `android.jar` method has. */
    private fun throwingStub(cv: ClassVisitor, access: Int, name: String, descriptor: String) {
        val mv = cv.visitMethod(access, name, descriptor, null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
        mv.visitInsn(Opcodes.DUP)
        mv.visitLdcInsn("Stub!")
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(Opcodes.ATHROW)
        val slots = org.objectweb.asm.Type.getArgumentsAndReturnSizes(descriptor) shr 2
        mv.visitMaxs(3, if (access and Opcodes.ACC_STATIC != 0) slots - 1 else slots)
        mv.visitEnd()
    }
}
