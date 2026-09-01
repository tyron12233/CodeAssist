package dev.ide.awt.interp

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds the compile-time `java.awt` / `javax.swing` API jar from the toolkit's own classes.
 *
 * A project that uses Swing has to COMPILE against those names, and the platform it compiles against often
 * does not have them: on device it is `android.jar`, and on the desktop it is `android.jar` too whenever an
 * Android SDK is installed, since that is what the IDE prefers. This jar fills that gap.
 *
 * It is generated rather than hand-written, by running [AwtNameRemapper] BACKWARDS over the toolkit
 * (`dev/ide/awt/Color` -> `java/awt/Color`), so the API a program compiles against cannot drift from the one
 * it runs on: they are the same classes. It is a compile-only artifact and must never reach a runtime, since
 * nothing may define a class in `java.*`.
 *
 * Two filters keep the toolkit's own plumbing out of the result:
 *  - a class survives only if the real JDK declares its remapped name, which drops `CanvasGraphics`,
 *    `ToolkitWindows`, `NoCanvas` and the Kotlin `Companion` holders without a hand-maintained list. The
 *    generator runs on the build JDK, which has `java.desktop`, so the check is simply whether the type exists.
 *  - a member, interface, or inner-class reference survives only if every type it names also exists there.
 *    One rule covers everything that would otherwise dangle: the host seams (`Window.attachBackend`, whose
 *    parameter is a toolkit type), the `Surface` interface `Window` implements, the Kotlin `Companion` fields
 *    and `DefaultConstructorMarker` constructors, and anything else the toolkit needs but the API does not
 *    have. A dangling reference in a compile classpath is a resolution error waiting for the one program that
 *    touches it.
 */
object SwingApiStubs {

    /** Toolkit -> real API, the inverse of the rewrite an interpreted program gets. */
    private val toApi = object : Remapper() {
        override fun map(internalName: String): String = AwtNameRemapper.originalName(internalName)
    }

    /** Write every eligible class under [classesDir] into [outJar]. Returns how many were written. */
    @JvmStatic
    fun generate(classesDir: File, outJar: File): Int {
        outJar.parentFile?.mkdirs()
        var written = 0
        JarOutputStream(outJar.outputStream().buffered()).use { jar ->
            classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.sorted().forEach { file ->
                val internalName = file.relativeTo(classesDir).path.removeSuffix(".class").replace(File.separatorChar, '/')
                if (!AwtNameRemapper.handlesToolkitName(internalName)) return@forEach
                val apiName = AwtNameRemapper.originalName(internalName)
                if (!existsInJdk(apiName)) return@forEach
                jar.putNextEntry(JarEntry("$apiName.class"))
                jar.write(stub(file.readBytes()))
                jar.closeEntry()
                written++
            }
        }
        return written
    }

    /** Remap one class to its API name, dropping everything that would not resolve against the real API. */
    private fun stub(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        val filter = object : ClassVisitor(Opcodes.ASM9, writer) {

            override fun visit(
                version: Int, access: Int, name: String, signature: String?,
                superName: String?, interfaces: Array<out String>?,
            ) {
                // Generic signatures can name types the filters below remove, and none of this API needs
                // them, so they are dropped wholesale rather than repaired.
                super.visit(
                    version, access, name, null, superName,
                    interfaces?.filter { existsInJdk(it) }?.toTypedArray() ?: emptyArray(),
                )
            }

            override fun visitField(
                access: Int, name: String, descriptor: String, signature: String?, value: Any?,
            ): FieldVisitor? =
                if (!resolvable(descriptor)) null else super.visitField(access, name, descriptor, null, value)

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (!resolvable(descriptor)) return null
                val out = super.visitMethod(access, name, descriptor, null, exceptions) ?: return null
                if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return out
                return StubBody(out, access, descriptor)
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (existsInJdk(name)) super.visitInnerClass(name, outerName, innerName, access)
            }

            /** Kotlin's @Metadata and friends name kotlin.* types and say nothing to a Java compiler. */
            override fun visitAnnotation(descriptor: String, visible: Boolean) = null
        }
        ClassReader(bytes).accept(ClassRemapper(filter, toApi), 0)
        return writer.toByteArray()
    }

    /**
     * Replaces a method's body with `throw new RuntimeException("Stub!")`, the shape `android.jar` itself uses.
     *
     * A compiler never reads the bodies of a classpath class, but the toolkit's bodies name types this jar does
     * not ship (Kotlin's `Intrinsics`, the `Companion` holders, the host seams), and a jar that references
     * nothing it cannot resolve is one nobody has to reason about. It also keeps the implementation out of an
     * artifact whose only job is to describe an API.
     */
    private class StubBody(
        private val out: MethodVisitor,
        access: Int,
        descriptor: String,
    ) : MethodVisitor(Opcodes.ASM9, null) {

        // Argument size counts the receiver slot, which a static method does not have.
        private val locals = (Type.getArgumentsAndReturnSizes(descriptor) shr 2) -
            if (access and Opcodes.ACC_STATIC != 0) 1 else 0

        override fun visitEnd() {
            out.visitCode()
            out.visitTypeInsn(Opcodes.NEW, RUNTIME_EXCEPTION)
            out.visitInsn(Opcodes.DUP)
            out.visitLdcInsn("Stub!")
            out.visitMethodInsn(Opcodes.INVOKESPECIAL, RUNTIME_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false)
            out.visitInsn(Opcodes.ATHROW)
            out.visitMaxs(3, maxOf(1, locals))
            out.visitEnd()
        }

        private companion object {
            const val RUNTIME_EXCEPTION = "java/lang/RuntimeException"
        }
    }

    /** Whether every object type this already-remapped descriptor names exists in the real API. */
    private fun resolvable(descriptor: String): Boolean =
        OBJECT_TYPE.findAll(descriptor).all { existsInJdk(it.groupValues[1]) }

    private val OBJECT_TYPE = Regex("L([^;<]+);")

    private fun existsInJdk(internalName: String): Boolean = runCatching {
        Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader())
    }.isSuccess

    /** Build entry point: `<classesDir> <outJar>`. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: SwingApiStubs <classesDir> <outJar>" }
        val count = generate(File(args[0]), File(args[1]))
        println("swing-api-stubs: wrote $count classes to ${args[1]}")
    }
}
