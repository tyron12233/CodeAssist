package dev.ide.kotlin.symbols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The symbol layer, running where it has to run.
 *
 * `JavaSymbolsPortTest` proves the port produces the same symbols as the real thing, but it can only do
 * that on the JVM, because the thing being compared to is JVM-only. So this is the other half: one real
 * class file, decoded on every platform the module builds for, asserting the answers a completion list
 * would actually use.
 *
 * `kotlin.jvm.functions.Function2` is the fixture because it is small and says a lot: three type
 * parameters, a generic supertype, and an `invoke` whose parameters and return are all type variables.
 */
class JavaSymbolsTest {

    @Test
    fun aGenericInterfaceIsReadTheSameOnEveryPlatform() {
        val shape = assertNotNull(JavaSymbols.read(FUNCTION2), "reading Function2.class")

        assertTrue(shape.isInterface, "Function2 is an interface")
        assertTrue(shape.isAbstract, "an interface is abstract")
        assertTrue(!shape.isFinal, "an interface never sets the final bit")

        assertEquals(listOf("P1", "P2", "R"), shape.typeParameters)
        assertEquals(
            listOf("java.lang.Object", "java.lang.Object", "java.lang.Object"),
            shape.typeParameterBounds.map { it.render() },
            "an unbounded parameter erases to Object",
        )
        // The supertypes come from the SIGNATURE, so `Function` keeps its argument rather than going raw.
        assertEquals(listOf("java.lang.Object", "kotlin.Function<R>"), shape.superTypes.map { it.render() })

        val invoke = assertNotNull(shape.members.firstOrNull { it.name == "invoke" }, "invoke")
        assertEquals(SymbolKind.METHOD, invoke.kind)
        assertEquals("R", invoke.type?.render())
        assertEquals(listOf("P1", "P2"), invoke.paramTypes.map { it?.render() })
        assertTrue(Modifier.ABSTRACT in invoke.modifiers, "invoke is abstract")
        assertEquals("kotlin.jvm.functions.Function2", invoke.declaringClassFqn)
        // Built from the ERASED descriptor, which is why the display says Object and the types say P1.
        assertEquals("(p0: Object, p1: Object): Object", invoke.signature)
        assertEquals(-1, invoke.varargParamIndex)
        assertTrue(invoke.paramNames.isEmpty(), "the stdlib is not compiled with -parameters")
    }

    private companion object {
        /** `kotlin/jvm/functions/Function2.class`, verbatim. */
        private val FUNCTION2: ByteArray = hex(
        "cafebabe00000034002301001e6b6f746c696e2f6a766d2f66756e6374696f6e732f46756e6374696f6e320700010100" +
        "683c50313a4c6a6176612f6c616e672f4f626a6563743b50323a4c6a6176612f6c616e672f4f626a6563743b523a4c6a" +
        "6176612f6c616e672f4f626a6563743b3e4c6a6176612f6c616e672f4f626a6563743b4c6b6f746c696e2f46756e6374" +
        "696f6e3c54523b3e3b0100106a6176612f6c616e672f4f626a65637407000401000f6b6f746c696e2f46756e6374696f" +
        "6e070006010006696e766f6b65010038284c6a6176612f6c616e672f4f626a6563743b4c6a6176612f6c616e672f4f62" +
        "6a6563743b294c6a6176612f6c616e672f4f626a6563743b01000d285450313b5450323b2954523b0100114c6b6f746c" +
        "696e2f4d657461646174613b0100026d760300000002030000000403000000000100016b030000000101000278690300" +
        "0000300100026431010066c080100a0218020a0208030a0218020a020805086618c0802a0608c080100120c0802a0608" +
        "01100220c0802a06080210032001320812041202480330044a1f10051a023802320610061a0238c080320610071a0238" +
        "0148c2a6c28204c2a206021008c2a8060901000264320100204c6b6f746c696e2f6a766d2f66756e6374696f6e732f46" +
        "756e6374696f6e323b01000250310100025032010001520100114c6b6f746c696e2f46756e6374696f6e3b0100027031" +
        "010002703201000d6b6f746c696e2d7374646c696201000c46756e6374696f6e732e6b740100095369676e6174757265" +
        "01000a536f7572636546696c6501001952756e74696d6556697369626c65416e6e6f746174696f6e7306010002000500" +
        "010007000000010401000800090001002000000002000a00030020000000020003002100000002001f00220000004900" +
        "01000b0005000c5b000349000d49000e49000f0010490011001249001300145b000173001500165b000a730017730018" +
        "73001973001a73001b73000873001c73001d73000973001e"
        )

        private fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
