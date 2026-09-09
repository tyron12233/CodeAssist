package dev.ide.lang.java

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.lang.java.index.JavaClassNamesIndex
import dev.ide.lang.java.index.JavaPackageTypesIndex
import dev.ide.platform.ContentHash
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NESTED types in the class-NAME index: a nested type is used, completed and imported by its OWN simple name
 * (`LayoutParams`, `Map.Entry`), so it is keyed that way with the dotted FQN an `import` line spells. It used
 * to be absent from the index entirely on the binary side (the `$` entry was skipped) and recorded under an
 * outer-less FQN on the source side, so nothing could offer "Import android.widget.LinearLayout.LayoutParams"
 * for an unresolved `LayoutParams`. Synthetic classes (anonymous, lambda/inline artefacts) stay out, and a
 * nested type is still not a DIRECT member of its package.
 */
class JavaNestedTypeIndexTest {

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

    /** A class file for [internalName] with the given access flags (nested or not: the entry path decides). */
    private fun classBytes(internalName: String, access: Int = Opcodes.ACC_PUBLIC): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, access, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun binaryNames(entry: String, access: Int = Opcodes.ACC_PUBLIC) =
        JavaClassNamesIndex.index(Input(IndexOrigin.LIBRARY, entry, b = classBytes(entry.removeSuffix(".class"), access)))

    @Test
    fun nestedLibraryTypeIsKeyedBySimpleNameWithADottedFqn() {
        val names = binaryNames("android/widget/LinearLayout\$LayoutParams.class")
        assertEquals(
            "android.widget.LinearLayout.LayoutParams",
            names["LayoutParams"]?.first()?.fqn,
            "a nested library type must be importable by its own simple name; got $names",
        )
    }

    @Test
    fun deeplyNestedTypeKeepsItsWholeChain() {
        val names = binaryNames("p/Outer\$Middle\$Inner.class")
        assertEquals("p.Outer.Middle.Inner", names["Inner"]?.first()?.fqn, "got $names")
    }

    @Test
    fun syntheticNestedClassesAreNotIndexed() {
        // An anonymous class, a lambda/inline artefact and a SAM wrapper are not declarations anyone imports.
        assertTrue(binaryNames("p/Outer\$1.class").isEmpty(), "anonymous class must not be indexed")
        assertTrue(binaryNames("p/Outer\$run\$1.class").isEmpty(), "lambda artefact must not be indexed")
        assertTrue(binaryNames("p/Outer\$\$inlined\$run\$1.class").isEmpty(), "inline artefact must not be indexed")
        assertTrue(binaryNames("p/sam\$java_util_Comparator\$0.class").isEmpty(), "SAM wrapper must not be indexed")
    }

    @Test
    fun nonPublicNestedTypeIsNotIndexed() {
        // Package-private (and, in class-file terms, private) nested types are not importable from elsewhere.
        assertTrue(binaryNames("p/Outer\$Hidden.class", access = 0).isEmpty(), "non-public nested type must be skipped")
    }

    @Test
    fun topLevelBinaryTypeIsUnchanged() {
        assertEquals("android.view.View", binaryNames("android/view/View.class")["View"]?.first()?.fqn)
    }

    @Test
    fun nestedSourceTypeCarriesItsOuterChain() {
        val src = "package app;\npublic class Outer {\n  public static class Inner { }\n}\n"
        val names = JavaClassNamesIndex.index(Input(IndexOrigin.SOURCE, "app/Outer.java", t = src, fileId = 3))
        assertEquals("app.Outer", names["Outer"]?.first()?.fqn, "got $names")
        assertEquals("app.Outer.Inner", names["Inner"]?.first()?.fqn, "the nested FQN must include its outer; got $names")
    }

    @Test
    fun packageTypesListsOnlyTheTopLevelSourceTypes() {
        val src = "package app;\npublic class Outer {\n  public static class Inner { }\n}\n"
        val byPkg = JavaPackageTypesIndex.index(Input(IndexOrigin.SOURCE, "app/Outer.java", t = src, fileId = 4))
        val fqns = byPkg["app"].orEmpty().map { it.fqn }
        assertTrue("app.Outer" in fqns, "the top-level type is a package member; got $fqns")
        assertTrue("app.Outer.Inner" !in fqns && "app.Inner" !in fqns, "a nested type is not a package member; got $fqns")
        assertNull(byPkg["app.Outer"], "nesting is not a package; got ${byPkg.keys}")
    }
}
