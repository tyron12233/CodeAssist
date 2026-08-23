package dev.ide.lang.kotlin.symbols

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A JVM PRIMITIVE array is Kotlin's specialised array class, not `Array<Boxed>`: `byte[]` is `ByteArray`,
 * `int[]` is `IntArray`. [JavaBytecode] used to decode every array as `kotlin.Array<elem>`, so a plain Java
 * API like `OutputStream.write(byte[])` looked like it took `Array<Byte>` and passing a `ByteArray` was
 * reported as a type mismatch.
 *
 * Both decode paths are covered: the erased descriptor (no generic signature) and the generic-SIGNATURE
 * attribute, which has its own array handling.
 */
class JavaBytecodeArrayTypeTest {

    /** `public class p/Sink` with one method per descriptor/signature pair, named `m0`, `m1`, … */
    private fun sinkBytes(methods: List<Pair<String, String?>>): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Sink", null, "java/lang/Object", null)
        methods.forEachIndexed { i, (descriptor, signature) ->
            cw.visitMethod(Opcodes.ACC_PUBLIC, "m$i", descriptor, signature, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 2); visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** The rendered first-parameter type of each method, in declaration order. */
    private fun paramTypes(methods: List<Pair<String, String?>>): List<String?> {
        val shape = JavaBytecode.read(sinkBytes(methods), null)!!
        return methods.indices.map { i ->
            shape.members.single { it.name == "m$i" }.paramTypes.single()?.qualifiedName
        }
    }

    @Test
    fun erasedPrimitiveArraysDecodeToTheSpecialisedArrayClass() {
        val types = paramTypes(
            listOf(
                "([B)V" to null, "([I)V" to null, "([J)V" to null, "([S)V" to null,
                "([C)V" to null, "([Z)V" to null, "([F)V" to null, "([D)V" to null,
            )
        )
        assertEquals(
            listOf(
                "kotlin.ByteArray", "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray",
                "kotlin.CharArray", "kotlin.BooleanArray", "kotlin.FloatArray", "kotlin.DoubleArray",
            ),
            types,
        )
    }

    @Test
    fun genericSignaturePrimitiveArraysDecodeToTheSpecialisedArrayClass() {
        // A signature attribute is emitted whenever ANY part of the method is generic, so a `byte[]` parameter
        // reaches the signature path too (here: a generic return type beside it).
        val types = paramTypes(
            listOf(
                "([B)Ljava/util/List;" to "([B)Ljava/util/List<Ljava/lang/String;>;",
                "([I)Ljava/util/List;" to "([I)Ljava/util/List<Ljava/lang/String;>;",
            )
        )
        assertEquals(listOf("kotlin.ByteArray", "kotlin.IntArray"), types)
    }

    /** Only PRIMITIVE elements specialise: a reference array stays `Array<E>`, and so does a nested array's
     *  outer dimension (`byte[][]` is `Array<ByteArray>` — ASM's `elementType` strips every dimension at once,
     *  so this also pins that each one gets re-wrapped). */
    @Test
    fun referenceAndNestedArraysStayGenericArray() {
        val shape = JavaBytecode.read(
            sinkBytes(listOf("([Ljava/lang/String;)V" to null, "([[B)V" to null)), null
        )!!
        val refArray = shape.members.single { it.name == "m0" }.paramTypes.single()!!
        assertEquals("kotlin.Array", refArray.qualifiedName)
        assertEquals("java.lang.String", refArray.typeArguments.single().qualifiedName)

        val nested = shape.members.single { it.name == "m1" }.paramTypes.single()!!
        assertEquals("kotlin.Array", nested.qualifiedName)
        assertEquals("kotlin.ByteArray", nested.typeArguments.single().qualifiedName)
    }

    /** A type variable never specialises: `T[]` is `Array<T>` whatever `T` is bound to. */
    @Test
    fun typeVariableArrayStaysGenericArray() {
        val shape = JavaBytecode.read(
            sinkBytes(listOf("([Ljava/lang/Object;)V" to "<T:Ljava/lang/Object;>([TT;)V")), null
        )!!
        val t = shape.members.single { it.name == "m0" }.paramTypes.single()!!
        assertEquals("kotlin.Array", t.qualifiedName)
        assertEquals("T", t.typeArguments.single().qualifiedName)
    }
}
