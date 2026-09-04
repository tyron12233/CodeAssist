package dev.ide.ksp

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ClassFileMembers] backs the member-level runtime floor, which FAILS builds — so its two answers have to
 * stay distinct: "this class does not declare that member" and "I could not read this class".
 */
class ClassFileMembersTest {

    private fun annotationBytes(vararg elements: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION,
            "com/example/Ann", null, "java/lang/Object", arrayOf("java/lang/annotation/Annotation"),
        )
        elements.forEach { cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, it, "()Ljava/lang/String;", null, null).visitEnd() }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun anAnnotationsElementsAreItsMethods() {
        val names = assertNotNull(ClassFileMembers.methodNames(annotationBytes("root", "rootPackage")))
        assertEquals(setOf("root", "rootPackage"), names)
    }

    @Test
    fun aClassWithNoMethodsReadsAsEmptyNotUnreadable() {
        assertEquals(emptySet(), ClassFileMembers.methodNames(annotationBytes()))
    }

    /**
     * Null, never an empty set: an unparseable entry (a truncated jar, a class file newer than the bundled
     * ASM) must not be read as "the member is missing" — that would block a build on a runtime that is fine.
     */
    @Test
    fun unreadableBytesAreUnknown() {
        assertNull(ClassFileMembers.methodNames(ByteArray(0)))
        assertNull(ClassFileMembers.methodNames("not a class file".toByteArray()))
        assertNull(
            ClassFileMembers.methodNames(annotationBytes("root").copyOfRange(0, 12)),
            "a truncated class file is unknown, not empty",
        )
    }

    /** Real bytes, not a synthetic fixture: this class's own compiled form. */
    @Test
    fun readsAKotlinCompiledClass() {
        val resource = "/" + ClassFileMembersTest::class.java.name.replace('.', '/') + ".class"
        val bytes = ClassFileMembersTest::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
        val names = assertNotNull(ClassFileMembers.methodNames(assertNotNull(bytes, "own class file not on the test classpath")))
        assertTrue("readsAKotlinCompiledClass" in names, "expected this test's own method: $names")
    }
}
