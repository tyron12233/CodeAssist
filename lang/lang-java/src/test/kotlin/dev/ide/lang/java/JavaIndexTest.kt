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

    /** Mirrors the real `LibraryInput` (memoized `bytes()` + a per-input `shared` memo) but records how many
     *  times `bytes()` is called and how many times each `shared` key's compute actually runs — so a test can
     *  assert the binary indexes parse a class ONCE, not once per index. */
    private class RecordingInput(
        override val origin: IndexOrigin,
        override val unitName: String?,
        private val b: ByteArray,
    ) : IndexInput {
        override val contentHash = ContentHash.of(b)
        override val sourcePath: Path? = null
        var bytesCalls = 0
            private set
        val computeRuns = HashMap<String, Int>()
        override fun bytes(): ByteArray { bytesCalls++; return b }
        override fun text(): String? = null
        override fun dom() = null

        private val memo = HashMap<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> shared(key: String, compute: () -> T): T {
            if (memo.containsKey(key)) return memo[key] as T
            computeRuns[key] = (computeRuns[key] ?: 0) + 1
            val v = compute(); memo[key] = v; return v
        }
    }

    @Test
    fun binaryIndexesShareOneBytecodeReadPerClass() {
        // classNames, packageTypes, and members each need the ASM class shape. They must share ONE
        // `JavaBytecode.read` (and one zip inflation) via `IndexInput.shared`, not re-parse the class per index —
        // the redundant-parse-per-class cold-build cost on android.jar (~40k classes × 3).
        val input = RecordingInput(IndexOrigin.LIBRARY, "com/foo/Greeter.class", greeterClassBytes())

        // Correctness is unchanged: each index still produces its entries.
        assertEquals("com.foo.Greeter", JavaClassNamesIndex.index(input)["Greeter"]?.first()?.fqn)
        assertEquals("com.foo.Greeter", JavaPackageTypesIndex.index(input)["com.foo"]?.first()?.fqn)
        assertNotNull(JavaMembersIndex.index(input)["greet"]?.firstOrNull(), "greet still indexed")

        // …but the class is read + inflated exactly once across all three.
        assertEquals(1, input.computeRuns["java.classfile"], "one shared JavaBytecode.read per class")
        assertEquals(1, input.bytesCalls, "one byte inflation per class")
    }

    @Test
    fun sourceDeclarationsFromPsiParse() {
        val src = "package com.foo;\npublic class Use {\n" +
            "  public static void main(String[] args) {}\n" +
            "  public int run(String a) { return 0; }\n" +
            "  int field;\n}"
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

        // Each member carries its encoded SHAPE (parameters + static flag + return type) so the Kotlin backend
        // resolves a same-project call with real arity — the fix for shapeless (0-param) cross-language members.
        val mainSig = byOwner["com.foo.Use"]?.first { it.name == "main" }?.signature
        val main = dev.ide.index.JavaSourceMemberCodec.decodeMethod(mainSig ?: "")
        assertEquals(true, main?.static, "`main` is static")
        assertEquals(listOf("String[]"), main?.paramTypes, "`main` param type recorded AS WRITTEN")
        assertEquals(listOf("args"), main?.paramNames, "`main` param name recorded")
        assertEquals("void", main?.returnType)

        val run = dev.ide.index.JavaSourceMemberCodec.decodeMethod(
            byOwner["com.foo.Use"]?.first { it.name == "run" }?.signature ?: "",
        )
        assertEquals(false, run?.static, "instance method `run` is not static")
        assertEquals(listOf("String"), run?.paramTypes)
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

    @Test
    fun sourceDocConstructorAndUndocumentedMethod() {
        val src = "package p;\npublic class Box {\n/** Make a box. */\npublic Box(int size, String label) {}\n" +
            "public int area(int w, int h) { return w * h; }\n}"
        val docs = JavaSourceDocIndex.index(Input(IndexOrigin.LIBRARY_SOURCE, "p/Box.java", t = src))
        val entries = docs["p.Box"]
        assertNotNull(entries, "keyed by owner FQN; got ${docs.keys}")
        // A constructor is keyed by the class simple name, with real param names + arity + its javadoc.
        assertTrue(
            entries.any { it.name == "Box" && it.names == listOf("size", "label") && it.doc?.contains("Make a box") == true },
            "constructor param names + javadoc; got $entries",
        )
        // An UNDOCUMENTED method still contributes real parameter names (bytecode has none) — doc null.
        assertTrue(
            entries.any { it.name == "area" && it.names == listOf("w", "h") && it.doc == null },
            "undocumented method still yields param names; got $entries",
        )
    }

    @Test
    fun sourceDocParamNamesEvenWithoutAnyJavadoc() {
        // No javadoc anywhere: Java bytecode carries no parameter names, so the source-doc index must STILL
        // emit them (unlike Kotlin, whose @Metadata has names and which skips a doc-less file).
        val src = "package p; public class U { public void set(int row, int col) {} }"
        val docs = JavaSourceDocIndex.index(Input(IndexOrigin.LIBRARY_SOURCE, "p/U.java", t = src))
        assertTrue(
            docs["p.U"]?.any { it.name == "set" && it.names == listOf("row", "col") } == true,
            "param names present without any javadoc; got ${docs["p.U"]}",
        )
    }

    @Test
    fun sourceDocNestedClassAndGenericParam() {
        val src = "package p;\npublic class Outer {\npublic static class Inner {\n/** Put. */\n" +
            "public void put(java.util.Map<String, Integer> m, int n) {}\n}\n}"
        val docs = JavaSourceDocIndex.index(Input(IndexOrigin.LIBRARY_SOURCE, "p/Outer.java", t = src))
        val inner = docs["p.Outer.Inner"]
        assertNotNull(inner, "nested member keyed by nested FQN; got ${docs.keys}")
        // The generic `Map<String, Integer>` is ONE parameter (its inner comma is not a param separator).
        assertTrue(
            inner.any { it.name == "put" && it.names == listOf("m", "n") && it.doc?.contains("Put") == true },
            "generic param counts as one; got $inner",
        )
    }
}
