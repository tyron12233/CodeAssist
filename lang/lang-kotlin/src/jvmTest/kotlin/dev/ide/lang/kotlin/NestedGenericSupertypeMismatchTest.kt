package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A library class whose GENERIC supertype is a nested class must be assignable to that nested class.
 *
 * The reported case is ViewPager2's: `FragmentStateAdapter extends RecyclerView.Adapter<FragmentViewHolder>`,
 * so `viewPager.adapter = MainPagerAdapter(activity)` is valid Kotlin. The class file names that supertype in
 * its Signature attribute as `RecyclerView$Adapter<…>`, and the decoder kept the `$`, so the supertype chain
 * held `RecyclerView$Adapter` while the setter's parameter was the dot-form `RecyclerView.Adapter`. The
 * assignment check reported "inferred type is MainPagerAdapter but Adapter was expected", and because that
 * error blocks the Compose preview, every @Preview in the file stopped rendering.
 *
 * The fixture jar mirrors that shape with ASM: `pg.Recycler.Adapter<VH : Recycler.Holder>`, a
 * `pg.PageAdapter : Recycler.Adapter<PageHolder>` carrying a generic signature, and a `pg.Pager` whose
 * `adapter` property takes the raw nested `Recycler.Adapter`.
 */
class NestedGenericSupertypeMismatchTest {

    @Test
    fun sourceSubclassAssignsToTheNestedLibrarySupertypeThroughASetter() {
        val codes = diagnosticCodes(
            """
            class MainPagerAdapter : pg.PageAdapter()
            fun f(pager: pg.Pager) {
                pager.adapter = MainPagerAdapter()
                pager.apply { adapter = MainPagerAdapter() }
            }
            """.trimIndent(),
        )
        assertTrue("kt.typeMismatch" !in codes, "a PageAdapter subclass is a Recycler.Adapter; got $codes")
    }

    @Test
    fun libraryClassAssignsToItsNestedGenericSupertype() {
        val codes = diagnosticCodes("fun f(a: pg.PageAdapter) { val x: pg.Recycler.Adapter<*> = a; println(x) }")
        assertTrue("kt.typeMismatch" !in codes, "PageAdapter's generic supertype is Recycler.Adapter; got $codes")
    }

    @Test
    fun anUnrelatedNestedTypeIsStillAMismatch() {
        // The check must still run: a holder is not an adapter.
        val codes = diagnosticCodes("fun f(h: pg.PageHolder) { val x: pg.Recycler.Adapter<*> = h; println(x) }")
        assertTrue("kt.typeMismatch" in codes, "a PageHolder is not a Recycler.Adapter; got $codes")
    }

    private fun diagnosticCodes(code: String): List<String?> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.code }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), buildFixtureJar())))

        private const val OBJ = "java/lang/Object"
        private const val PUB = Opcodes.ACC_PUBLIC
        private const val ABSTRACT = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
        private const val NESTED = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC

        private fun buildFixtureJar(): Path {
            val classes = linkedMapOf(
                "pg/Recycler" to recycler(),
                "pg/Recycler\$Holder" to holder(),
                "pg/Recycler\$Adapter" to adapter(),
                "pg/PageHolder" to pageHolder(),
                "pg/PageAdapter" to pageAdapter(),
                "pg/Pager" to pager(),
            )
            val jar = Files.createTempFile("nested-generic-supertype", ".jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                classes.forEach { (name, bytes) ->
                    out.putNextEntry(JarEntry("$name.class"))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            return jar
        }

        private fun ClassWriter.innerClasses() {
            visitInnerClass("pg/Recycler\$Holder", "pg/Recycler", "Holder", NESTED or Opcodes.ACC_ABSTRACT)
            visitInnerClass("pg/Recycler\$Adapter", "pg/Recycler", "Adapter", NESTED or Opcodes.ACC_ABSTRACT)
        }

        private fun ClassWriter.defaultConstructor() {
            visitMethod(PUB, "<init>", "()V", null, null).visitEnd()
        }

        /** class Recycler { static abstract class Holder; static abstract class Adapter<VH extends Holder> } */
        private fun recycler(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, PUB, "pg/Recycler", null, OBJ, null)
            innerClasses()
            defaultConstructor()
            visitEnd()
        }.toByteArray()

        private fun holder(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, ABSTRACT, "pg/Recycler\$Holder", null, OBJ, null)
            innerClasses()
            defaultConstructor()
            visitEnd()
        }.toByteArray()

        private fun adapter(): ByteArray = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, ABSTRACT, "pg/Recycler\$Adapter",
                "<VH:Lpg/Recycler\$Holder;>Ljava/lang/Object;", OBJ, null,
            )
            innerClasses()
            defaultConstructor()
            visitEnd()
        }.toByteArray()

        /** class PageHolder extends Recycler.Holder — mirrors FragmentViewHolder. */
        private fun pageHolder(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, PUB, "pg/PageHolder", null, "pg/Recycler\$Holder", null)
            innerClasses()
            defaultConstructor()
            visitEnd()
        }.toByteArray()

        /** abstract class PageAdapter extends Recycler.Adapter<PageHolder> — mirrors FragmentStateAdapter, whose
         *  Signature attribute names the nested supertype in its flat `$` form. */
        private fun pageAdapter(): ByteArray = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, ABSTRACT, "pg/PageAdapter",
                "Lpg/Recycler\$Adapter<Lpg/PageHolder;>;", "pg/Recycler\$Adapter", null,
            )
            innerClasses()
            defaultConstructor()
            visitEnd()
        }.toByteArray()

        /** class Pager { void setAdapter(Recycler.Adapter); Recycler.Adapter getAdapter(); } — mirrors ViewPager2's
         *  raw-typed accessors. */
        private fun pager(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, PUB, "pg/Pager", null, OBJ, null)
            innerClasses()
            defaultConstructor()
            visitMethod(PUB, "setAdapter", "(Lpg/Recycler\$Adapter;)V", null, null).visitEnd()
            visitMethod(PUB, "getAdapter", "()Lpg/Recycler\$Adapter;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()
    }
}
