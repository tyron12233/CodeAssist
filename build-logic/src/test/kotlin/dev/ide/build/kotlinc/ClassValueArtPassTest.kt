package dev.ide.build.kotlinc

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ClassValueArtPass] must relocate EVERY `java.lang.ClassValue` reference (absent on ART) in `MethodHandleCache`
 * to the shipped shim — both the inner `ConcurrentMapClassValue extends ClassValue` superclass AND the top-level
 * class's `static final ClassValue CACHE` field + its `getstatic`/`invokevirtual ClassValue.get`. Verified purely
 * in bytecode (no device/APK), including that no residual `java/lang/ClassValue` reference survives.
 */
class ClassValueArtPassTest {

    private val pass = ClassValueArtPass()
    private val target = "com.intellij.util.messages.impl.MethodHandleCache"
    private val targetInternal = target.replace('.', '/')
    private val shim = "dev/ide/lang/jdt/compat/ClassValue"
    private val classValue = "java/lang/ClassValue"

    @Test
    fun handlesEveryClassValueUserAndInnerClasses() {
        // The reported crash + its inner classes.
        assertTrue(pass.handles(target))
        assertTrue(pass.handles("$target\$ConcurrentMapClassValue"), "the ClassValue-subclass inner class must be handled")
        assertTrue(pass.handles("$target\$1"), "synthetic inner classes must be handled")
        // The proactively-patched ClassValue users found in the bundled platform jar.
        assertTrue(pass.handles("com.intellij.util.ClearableClassValue"))
        assertTrue(pass.handles("com.intellij.serialization.PropertyCollector"))
        assertTrue(pass.handles("com.intellij.serialization.PropertyCollector\$PropertyCollectorListClassValue"))
        assertTrue(pass.handles("com.intellij.openapi.util.DefaultJDOMExternalizer"))
        assertTrue(pass.handles("com.intellij.util.xmlb.XmlSerializerPropertyCollectorListClassValue"))
        // Unrelated classes are left alone.
        assertFalse(pass.handles("com.intellij.util.messages.impl.MessageBusImpl"), "unrelated bus classes are left alone")
        assertFalse(pass.handles("com.intellij.util.messages.impl.MethodHandleCacheHelper"), "must not prefix-match a sibling")
        assertFalse(pass.handles("java.lang.Object"))
    }

    @Test
    fun rewritesTheClassValueSuperclassInTheInnerSubclass() {
        val bytes = runPass(concurrentMapClassValueShape())
        val reader = ClassReader(bytes)
        assertEquals(shim, reader.superName, "the superclass must be relocated to the ART-safe shim")
        assertFalse(referencesClassValue(bytes), "no residual java/lang/ClassValue reference may remain")
    }

    @Test
    fun rewritesTheStaticClassValueFieldAndItsAccessesInTheTopLevelClass() {
        val bytes = runPass(methodHandleCacheShape())
        assertFalse(
            referencesClassValue(bytes),
            "the CACHE field type + getstatic/invokevirtual must all be relocated; a java/lang/ClassValue reference survived",
        )
        assertTrue(referencesShim(bytes), "the field + accesses must now reference the shim")
    }

    private fun runPass(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(pass.visitor(target, writer), 0)
        return writer.toByteArray()
    }

    /** `MethodHandleCache$ConcurrentMapClassValue extends java/lang/ClassValue<…>` with a `super()`-calling ctor. */
    private fun concurrentMapClassValueShape(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_FINAL, "$targetInternal\$ConcurrentMapClassValue",
            "L$classValue<Ljava/util/concurrent/ConcurrentMap;>;", classValue, null,
        )
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, classValue, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PROTECTED, "computeValue", "(Ljava/lang/Class;)Ljava/lang/Object;", null, null).apply {
            visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 2); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Top-level `MethodHandleCache` with `static final ClassValue CACHE` + a `compute` reading `CACHE.get(...)`. */
    private fun methodHandleCacheShape(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_FINAL, targetInternal, null, "java/lang/Object", null)
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "CACHE", "L$classValue;",
            "L$classValue<Ljava/util/concurrent/ConcurrentMap;>;", null,
        ).visitEnd()
        cw.visitMethod(Opcodes.ACC_STATIC, "compute", "(Ljava/lang/Class;)Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, targetInternal, "CACHE", "L$classValue;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, classValue, "get", "(Ljava/lang/Class;)Ljava/lang/Object;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Scans the class's constant pool (UTF-8 entries) for an [internalName] reference. */
    private fun referencesType(bytes: ByteArray, internalName: String): Boolean {
        val needle = internalName.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun referencesClassValue(bytes: ByteArray) = referencesType(bytes, classValue)
    private fun referencesShim(bytes: ByteArray) = referencesType(bytes, shim)
}
