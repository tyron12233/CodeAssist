package dev.ide.lang.java

import dev.ide.index.AnnotationIndex
import dev.ide.index.EntryPointIndex
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.SubtypeIndex
import dev.ide.lang.java.index.JavaClassNamesIndex
import dev.ide.lang.java.index.JavaMainIndex
import dev.ide.lang.java.index.JavaPackageTypesIndex
import dev.ide.lang.java.index.JavaMembersByOwnerIndex
import dev.ide.lang.java.index.JavaMembersIndex
import dev.ide.lang.java.index.JavaSourceAnnotationIndex
import dev.ide.lang.java.index.JavaSourceDocIndex
import dev.ide.lang.java.index.JavaSourceSubtypeIndex
import dev.ide.lang.java.index.JavaSourceSymbolsIndex
import dev.ide.platform.ContentHash
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step-B/C verification: the IntelliJ-PSI backend's indexes produce the same neutral values the JDT indexes
 * did — binary from ASM bytecode, source from a structural PSI parse.
 */
class JavaIndexTest {

    private class Input(
        override val origin: IndexOrigin,
        override val unitName: String?,
        private val b: ByteArray = ByteArray(0),
        private val t: String? = null,
        override val fileId: Int = -1,
        override val sourcePath: Path? = null,
    ) : IndexInput {
        override val contentHash = ContentHash.of(t?.toByteArray() ?: b)
        override fun bytes(): ByteArray = b
        override fun text(): String? = t
        override fun dom() = null
    }

    /** A `public class com.foo.Greeter { private int count; public String greet(String); }` in bytecode. */
    private fun greeterClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/foo/Greeter", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun binaryMembersFromAsmMatchJdtShape() {
        val input = Input(IndexOrigin.LIBRARY, "com/foo/Greeter.class", b = greeterClassBytes())
        val members = JavaMembersIndex.index(input)
        val greet = members["greet"]?.firstOrNull()
        assertNotNull(greet, "greet method should be indexed")
        assertEquals("com.foo.Greeter", greet.owner)
        assertEquals("method", greet.kind)
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", greet.signature, "erased JVM method descriptor")
        val count = members["count"]?.firstOrNull()
        assertNotNull(count, "field should be indexed")
        assertEquals("field", count.kind)
        assertEquals("I", count.signature, "field descriptor")
    }

    @Test
    fun binaryClassNameIsPublicKeyedBySimpleName() {
        val input = Input(IndexOrigin.LIBRARY, "com/foo/Greeter.class", b = greeterClassBytes())
        assertEquals("com.foo.Greeter", JavaClassNamesIndex.index(input)["Greeter"]?.first()?.fqn)
    }

    /** A class with just the given access flags (no members), for kind classification. */
    private fun typeBytes(access: Int, internalName: String, superName: String, interfaces: Array<String>?): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, access, internalName, null, superName, interfaces)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun binaryTypeKindReflectsAccessFlags() {
        // A library annotation/interface/enum class must be labeled by its real kind, not a blanket "class".
        // The Kotlin `@`-annotation completion filter keeps only ANNOTATION_TYPE candidates, so a library
        // annotation (`@Composable`, `@Deprecated`, …) mislabeled "class" would be dropped -> empty popup.
        val anno = Input(
            IndexOrigin.LIBRARY, "com/foo/MyAnno.class",
            b = typeBytes(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "com/foo/MyAnno", "java/lang/Object", arrayOf("java/lang/annotation/Annotation"),
            ),
        )
        assertEquals("annotation", JavaClassNamesIndex.index(anno)["MyAnno"]?.first()?.kind, "classNames kind")
        assertEquals("annotation", JavaPackageTypesIndex.index(anno)["com.foo"]?.first()?.kind, "packageTypes kind")

        val iface = Input(
            IndexOrigin.LIBRARY, "com/foo/MyIface.class",
            b = typeBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, "com/foo/MyIface", "java/lang/Object", null),
        )
        assertEquals("interface", JavaClassNamesIndex.index(iface)["MyIface"]?.first()?.kind)

        val enum = Input(
            IndexOrigin.LIBRARY, "com/foo/MyEnum.class",
            b = typeBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM, "com/foo/MyEnum", "java/lang/Enum", null),
        )
        assertEquals("enum", JavaClassNamesIndex.index(enum)["MyEnum"]?.first()?.kind)

        // A plain class still reads as "class".
        val cls = Input(IndexOrigin.LIBRARY, "com/foo/Greeter.class", b = greeterClassBytes())
        assertEquals("class", JavaClassNamesIndex.index(cls)["Greeter"]?.first()?.kind)
    }

    @Test
    fun sourceDeclarationsFromPsiParse() {
        val src = "package com.foo;\npublic class Use { public int run(String a) { return 0; } int field; }"
        val input = Input(IndexOrigin.SOURCE, "com/foo/Use.java", t = src, fileId = 7)

        val syms = JavaSourceSymbolsIndex.index(input)
        assertTrue(setOf("Use", "run", "field").all { it in syms.keys }, "source decls; got ${syms.keys}")
        assertEquals(7, syms["run"]?.first()?.fileId)

        assertEquals("com.foo.Use", JavaClassNamesIndex.index(input)["Use"]?.first()?.fqn)

        val byOwner = JavaMembersByOwnerIndex.index(input)
        assertTrue(
            byOwner["com.foo.Use"]?.any { it.name == "run" } == true,
            "public method run under com.foo.Use; got ${byOwner.keys}",
        )
        // `field` is package-private -> excluded from the public-only membersByOwner index.
        assertTrue(byOwner["com.foo.Use"]?.none { it.name == "field" } == true, "non-public field excluded")
    }

    @Test
    fun subtypeRelationsFromPsi() {
        val src = "package p; class Impl extends Base implements Runnable {}"
        val subs = JavaSourceSubtypeIndex.index(Input(IndexOrigin.SOURCE, "p/Impl.java", t = src, fileId = 1))
        assertTrue(subs[SubtypeIndex.key("Base")]?.any { it.fqn == "p.Impl" } == true, "Impl extends Base; got ${subs.keys}")
        assertTrue(subs[SubtypeIndex.key("Runnable")]?.any { it.fqn == "p.Impl" } == true, "Impl implements Runnable")
    }

    @Test
    fun annotationRelationsFromPsi() {
        val src = "package p; @Deprecated class X { @Override public void m(){} }"
        val anns = JavaSourceAnnotationIndex.index(Input(IndexOrigin.SOURCE, "p/X.java", t = src, fileId = 2))
        assertTrue(anns[AnnotationIndex.key("Deprecated")]?.any { it.fqn == "p.X" } == true, "type @Deprecated; got ${anns.keys}")
        assertTrue(anns[AnnotationIndex.key("Override")]?.any { it.fqn == "p.X#m" } == true, "member @Override on p.X#m")
    }

    @Test
    fun mainEntryPointFromPsi() {
        val src = "package p; public class M { public static void main(String[] a){} }"
        val mains = JavaMainIndex.index(Input(IndexOrigin.SOURCE, "p/M.java", t = src, fileId = 3))
        val hit = mains[EntryPointIndex.KEY]?.firstOrNull()
        assertNotNull(hit, "a public static void main should be an entry point")
        assertEquals("p.M", hit.fqn)
        assertEquals(false, hit.instance, "static main is not instance-invoked")
    }

    @Test
    fun sourceDocFromLibrarySource() {
        val src = "package p;\n/** Type doc. */\npublic class D {\n/** Method doc. */\npublic void run(int count){}\n}"
        val docs = JavaSourceDocIndex.index(Input(IndexOrigin.LIBRARY_SOURCE, "p/D.java", t = src))
        val entries = docs["p.D"]
        assertNotNull(entries, "docs keyed by owner FQN; got ${docs.keys}")
        assertTrue(entries.any { it.name == "" && it.doc?.contains("Type doc") == true }, "type javadoc as empty-name entry")
        assertTrue(
            entries.any { it.name == "run" && it.names == listOf("count") && it.doc?.contains("Method doc") == true },
            "method param names + javadoc",
        )
    }
}
