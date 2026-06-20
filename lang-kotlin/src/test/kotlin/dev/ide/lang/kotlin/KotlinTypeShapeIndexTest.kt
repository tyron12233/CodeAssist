package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.index.TypeShapeExternalizer
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `kotlin.typeShape` index: that the PRODUCER extracts a generic member shape from bytecode, the codec
 * ROUND-TRIPS it, and the CONSUMER ([KotlinSymbolService] via the analyzer) resolves members + generics
 * through the index — including binding a generic factory's type argument and the per-class type parameter.
 */
class KotlinTypeShapeIndexTest {

    @Test
    fun producerExtractsGenericShape() {
        val shapes = produce()
        val box = shapes["gen.Box"] ?: error("no gen.Box shape; got ${shapes.keys}")
        assertEquals(listOf("E"), box.typeParameters, "Box declares <E>")
        val of = box.members.first { it.name == "of" }
        assertEquals(listOf("E"), of.typeParameters, "of declares its own <E>")
        assertEquals("gen.Box", (of.type as KotlinType).qualifiedName, "of returns Box<…>")
        assertTrue((of.type as KotlinType).typeArguments.singleOrNull()?.let { (it as KotlinType).isTypeParameter } == true,
            "of returns Box<E> (E a type parameter)")
        val get = box.members.first { it.name == "get" }
        assertTrue((get.type as KotlinType).isTypeParameter, "get returns the type parameter E")
    }

    @Test
    fun codecRoundTripsGenerics() {
        val box = produce()["gen.Box"]!!
        val back = roundTrip(box)
        assertEquals(box.typeParameters, back.typeParameters)
        val of = back.members.first { it.name == "of" }
        assertEquals(listOf("E"), of.typeParameters)
        assertEquals("gen.Box", (of.type as KotlinType).qualifiedName)
        assertEquals(1, of.paramTypes.size)
        assertTrue((of.paramTypes.single() as KotlinType).isTypeParameter, "param E survives the round trip")
    }

    @Test
    fun consumerResolvesGenericsThroughIndex() {
        // val a = gen.Box.of(t) → Box<Txt>; a.get() → Txt → `.upper` resolves, all served by the index.
        val hs = hints("fun f(t: gen.Txt) { val a = gen.Box.of(t) }")
        assertTrue(hs.any { it == ": Box<Txt>" }, "factory type-arg bound via index; got $hs")

        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(t: gen.Txt) { val a = gen.Box.of(t); a.get().| }")
        }
        assertTrue(r.items.any { it.symbol?.name == "upper" }, "get(): E=Txt via index; got ${r.items.mapNotNull { i -> i.symbol?.name }}")
    }

    @Test
    fun indexShapeIsActuallyConsulted() {
        // The fake index serves a SENTINEL member on gen.Txt that the live bytecode doesn't have — its
        // presence on `t.` proves member enumeration came from the index, not the live fallback.
        val r = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(t: gen.Txt) { t.| }")
        }
        val names = r.items.mapNotNull { it.symbol?.name }
        assertTrue("fromIndex" in names, "the index-only sentinel must surface; got $names")
        assertTrue("upper" in names, "the real method (also in the indexed shape) must surface; got $names")
    }

    private fun hints(code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.inlayHints!!.hints(doc.file, TextRange(0, code.length)) }
            .map { it.parts.joinToString("") { p -> p.text } }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val classBytes = buildClasses()
        val fixtureJar: Path = buildJar(classBytes)

        /** The persisted shapes, served by a fake index — built by running the REAL producer over the fixture
         *  `.class` bytes and passing each through the codec, with a sentinel added to `gen.Txt`. */
        private val served: Map<String, TypeShape> = produce().mapValues { (fqn, shape) ->
            val rt = roundTrip(shape)
            if (fqn != "gen.Txt") rt
            else TypeShape(rt.typeParameters, rt.typeParameterBounds, rt.supertypes,
                rt.members + KotlinSymbol("fromIndex", SymbolKind.METHOD, origin = SymbolOrigin(false, null), signature = "(): Txt"))
        }

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id.value == "kotlin.typeShape") served[key]?.let { sequenceOf(it as V) } ?: emptySequence()
                else emptySequence()
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fixtureJar)))
            .apply { indexService = fakeIndex }

        /** Run the real index producer over each fixture class file → owner FQN -> shape. */
        private fun produce(): Map<String, TypeShape> {
            val out = HashMap<String, TypeShape>()
            classBytes.forEach { (entry, bytes) ->
                KotlinTypeShapeIndex.index(FakeInput(entry, bytes)).forEach { (k, v) -> out[k] = v.first() }
            }
            return out
        }

        private fun roundTrip(shape: TypeShape): TypeShape {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { TypeShapeExternalizer.write(it, shape) }
            return DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { TypeShapeExternalizer.read(it) }
        }

        private class FakeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: Path? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }

        private const val OBJ = "java/lang/Object"

        private fun buildClasses(): Map<String, ByteArray> = linkedMapOf(
            "gen/Txt.class" to run {
                val cw = ClassWriter(0)
                cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "gen/Txt", null, OBJ, null)
                cw.visitMethod(Opcodes.ACC_PUBLIC, "upper", "()Lgen/Txt;", null, null).visitEnd()
                cw.visitEnd(); cw.toByteArray()
            },
            "gen/Box.class" to run {
                val cw = ClassWriter(0)
                cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                    "gen/Box", "<E:L$OBJ;>L$OBJ;", OBJ, null)
                cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "of", "(L$OBJ;)Lgen/Box;",
                    "<E:L$OBJ;>(TE;)Lgen/Box<TE;>;", null).visitEnd()
                cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "get", "()L$OBJ;", "()TE;", null).visitEnd()
                cw.visitEnd(); cw.toByteArray()
            },
        )

        private fun buildJar(classes: Map<String, ByteArray>): Path {
            val jar = Files.createTempFile("typeshape-fixture", ".jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                classes.forEach { (name, bytes) -> out.putNextEntry(JarEntry(name)); out.write(bytes); out.closeEntry() }
            }
            return jar
        }
    }
}
