package dev.ide.lang.java

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.lang.java.index.JavaStaticMembersIndex
import dev.ide.platform.ContentHash
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The static-members index holds what an `import static` can name: the public static methods and fields of a
 * public top-level library class, keyed by member name with the class as the value. Instance members, private
 * or synthetic statics, and the members of a non-public or nested class stay out.
 */
class JavaStaticMembersIndexTest {

    private class Input(
        override val unitName: String?,
        private val b: ByteArray,
        override val origin: IndexOrigin = IndexOrigin.LIBRARY,
        private val t: String? = null,
    ) : IndexInput {
        override val sourcePath: String? = null
        override val contentHash = ContentHash.of(t?.toByteArray() ?: b)
        override fun bytes(): ByteArray = b
        override fun text(): String? = t
        override fun dom() = null
    }

    @Test
    fun aProjectClassContributesItsNonPrivateStatics() {
        val source = """
            package app.util;
            public class Texts {
                public static String shout(String s) { return s.toUpperCase(); }
                static int quiet() { return 0; }
                private static void hidden() {}
                public String instance() { return ""; }
                public static final int LIMIT = 3;
                public static class Nested { public static void deep() {} }
            }
        """.trimIndent()
        val indexed = JavaStaticMembersIndex.index(Input("Texts.java", ByteArray(0), IndexOrigin.SOURCE, source))
        assertEquals(setOf("shout", "quiet", "LIMIT", "deep"), indexed.keys, "got $indexed")
        assertEquals(listOf("app.util.Texts"), indexed.getValue("shout").toList())
        assertEquals(listOf("app.util.Texts.Nested"), indexed.getValue("deep").toList())
    }

    private fun index(internalName: String, classAccess: Int = Opcodes.ACC_PUBLIC): Map<String, Collection<String>> {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, classAccess, internalName, null, "java/lang/Object", null)
        val publicStatic = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
        cw.visitMethod(publicStatic, "assertEquals", "(II)V", null, null).visitEnd()
        cw.visitMethod(publicStatic, "assertEquals", "(JJ)V", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "instanceMethod", "()V", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "hidden", "()V", null, null).visitEnd()
        cw.visitMethod(publicStatic or Opcodes.ACC_SYNTHETIC, "access\$000", "()V", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).visitEnd()
        cw.visitField(publicStatic or Opcodes.ACC_FINAL, "MAX", "I", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd()
        cw.visitEnd()
        return JavaStaticMembersIndex.index(Input("$internalName.class", cw.toByteArray()))
    }

    @Test
    fun publicStaticsAreKeyedByNameWithTheirClass() {
        val indexed = index("org/junit/Assert")
        assertEquals(setOf("assertEquals", "MAX"), indexed.keys, "only public statics, each name once; got $indexed")
        assertEquals(listOf("org.junit.Assert"), indexed.getValue("assertEquals").toList())
    }

    @Test
    fun aNonPublicOrNestedClassContributesNothing() {
        assertTrue(index("org/junit/Hidden", classAccess = 0).isEmpty(), "a package-private class cannot be imported from")
        assertTrue(index("org/junit/Assert\$Inner").isEmpty(), "a nested class's statics are left out")
    }
}
