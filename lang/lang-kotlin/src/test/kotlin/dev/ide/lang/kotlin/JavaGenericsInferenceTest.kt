package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
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
 * Java-generics inference over bytecode SIGNATURES: binding a generic call's type arguments
 * (`Lst.of(t)` → `Lst<Txt>`), propagating them through a generic supertype (`Lst<Txt> : Coll<Txt>`, so
 * `stream()` → `Strm<Txt>`), and typing a lambda passed to a Java SAM (`map { it }` → `it: Txt`).
 *
 * The real `java.util.List`/`Stream`/`Function` aren't on the unit-test classpath (they live in the JDK
 * module image, not a jar), so a tiny fixture jar with the same generic SHAPE is synthesized with ASM.
 */
class JavaGenericsInferenceTest {

    @Test
    fun genericStaticFactoryBindsTypeArgument() {
        // val a = gen.Lst.of(t)  →  Lst<Txt>
        val hs = hints("fun f(t: gen.Txt) { val a = gen.Lst.of(t) }")
        assertTrue(hs.any { it == ": Lst<Txt>" }, "val a should hint ': Lst<Txt>'; got $hs")
    }

    @Test
    fun typeArgPropagatesThroughGenericSupertypeAndSam() = runBlocking {
        // a.stream() (inherited from Coll<E>) → Strm<Txt>; .map { it } types `it` as Txt via the Func SAM.
        val r = analyzer.completeAtCaret(
            srcDir, "Use.kt",
            "fun f(t: gen.Txt) { val a = gen.Lst.of(t); a.stream().map { it.| } }",
        )
        assertTrue(r.items.any { it.symbol?.name == "upper" }, "it should be Txt (member 'upper'); got ${r.items.map { i -> i.symbol?.name ?: i.label }}")
    }

    @Test
    fun samLambdaResultBindsChainTail() {
        // map { it.upper() } binds R = Txt, so toList() → Lst<Txt>.
        val hs = hints("fun f(t: gen.Txt) { val out = gen.Lst.of(t).stream().map { it.upper() }.toList() }")
        assertTrue(hs.any { it == ": Lst<Txt>" }, "val out should hint ': Lst<Txt>'; got $hs")
    }

    @Test
    fun javaStringResultExposesKotlinExtensions() = runBlocking {
        // A Java method returning `java.lang.String` is enumerated as `kotlin.String`, so Kotlin's stdlib
        // String extensions (and members) appear on member access — the reported `toString().<caret>` case.
        val ext = analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(t: gen.Txt) { t.name().upper| }")
            .items.mapNotNull { it.symbol?.name }
        assertTrue("uppercase" in ext, "String extension 'uppercase' should appear on a java.lang.String result; got $ext")
        val mem = analyzer.completeAtCaret(srcDir, "Use.kt", "fun f(t: gen.Txt) { t.name().len| }")
            .items.mapNotNull { it.symbol?.name }
        assertTrue("length" in mem, "String member 'length' should appear; got $mem")
    }

    @Test
    fun constructorArgCountIsValidated() {
        // gen.Widget has constructors (Txt) and (Txt, Int).
        assertTrue("kt.constructorArgs" !in ctorCodes("fun f(t: gen.Txt) { gen.Widget(t) }"), "1 arg matches Widget(Txt)")
        assertTrue("kt.constructorArgs" !in ctorCodes("fun f(t: gen.Txt) { gen.Widget(t, 1) }"), "2 args match Widget(Txt, Int)")
        assertTrue("kt.constructorArgs" in ctorCodes("fun f(t: gen.Txt) { gen.Widget() }"), "0 args fits no Widget constructor")
        assertTrue("kt.constructorArgs" in ctorCodes("fun f(t: gen.Txt) { gen.Widget(t, t, t) }"), "3 args fits no Widget constructor")
    }

    @Test
    fun constructorArgTypeIsValidated() {
        // gen.Num(int): a String literal where an Int is expected is a confident, subtyping-free mismatch.
        assertTrue("kt.typeMismatch" !in ctorCodes("fun f() { gen.Num(5) }"), "Int arg matches Num(Int)")
        assertTrue("kt.typeMismatch" in ctorCodes("fun f() { gen.Num(\"x\") }"), "String arg to Num(Int) is a mismatch")
    }

    @Test
    fun genericMethodReturnUsesTheExpectedTypeNotItsBound() {
        // `<T extends View> T findViewById(int)` (gen.Finder mirrors Android's) assigned to a `Button`: the
        // EXPECTED type pins T = Button (`Button <: View`), so the result is Button, not the erased bound View.
        // The reported false "inferred type is View but Button was expected" — the constraint solver had fixed
        // T to its FIRST upper bound (the declared bound View, registered before the expected-type bound Button).
        assertTrue(
            "kt.typeMismatch" !in ctorCodes("fun f(v: gen.Finder) { val b: gen.Button = v.findViewById(0) }"),
            "findViewById's T must infer to the expected Button, not the bound View",
        )
        // Assigning to the bound itself (T = View) stays valid.
        assertTrue(
            "kt.typeMismatch" !in ctorCodes("fun f(v: gen.Finder) { val w: gen.View = v.findViewById(0) }"),
            "assigning findViewById to the bound View is fine",
        )
    }

    @Test
    fun nestedLibraryTypeThroughErasedMemberMatchesTheDotFormDeclaration() {
        // The reported layout-params false mismatch (`inferred type is LayoutParams but ViewGroup$LayoutParams
        // was expected`): a nested library type reached through an ERASED member (`Panel.getParams():
        // Panel.Params`, mirroring `getLayoutParams(): ViewGroup.LayoutParams`) arrived in `$`-nested form
        // while the declared `gen.Panel.Params` was dot-form, so the assignment check false-flagged them as
        // different types even though they are the SAME class.
        assertTrue(
            "kt.typeMismatch" !in ctorCodes("fun f(p: gen.Panel) { val x: gen.Panel.Params = p.getParams() }"),
            "the erased nested return type must match the dot-form declared type",
        )
        // A nested SUBTYPE assigned to its nested supertype (the `FrameLayout.LayoutParams` → `ViewGroup.LayoutParams`
        // case) must also match — the supertype walk now compares dot-form names on both sides.
        assertTrue(
            "kt.typeMismatch" !in ctorCodes("fun f(p: gen.Panel) { val x: gen.Panel.Params = p.getSub() }"),
            "a nested subtype must be assignable to its nested supertype",
        )
    }

    private fun ctorCodes(code: String): List<String?> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.code }
    }

    private fun hints(code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.inlayHints!!.hints(doc.file, TextRange(0, code.length)) }
            .map { it.parts.joinToString("") { p -> p.text } }
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val fixtureJar: Path = buildFixtureJar()
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fixtureJar)))

        /** A jar of generic interfaces/classes mirroring `List`/`Collection`/`Stream`/`Function`/`String`. */
        private fun buildFixtureJar(): Path {
            val classes = linkedMapOf(
                "gen/Txt" to txt(),
                "gen/Func" to func(),
                "gen/Coll" to coll(),
                "gen/Lst" to lst(),
                "gen/Strm" to strm(),
                "gen/Widget" to widget(),
                "gen/Num" to num(),
                "gen/View" to view(),
                "gen/Button" to button(),
                "gen/Finder" to finder(),
                "gen/Panel" to panel(),
                "gen/Panel\$Params" to panelParams(),
                "gen/Panel\$SubParams" to panelSubParams(),
            )
            val jar = Files.createTempFile("gen-fixture", ".jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                classes.forEach { (name, bytes) ->
                    out.putNextEntry(JarEntry("$name.class"))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            return jar
        }

        private const val OBJ = "java/lang/Object"
        private const val V = Opcodes.V1_8
        private const val PUB = Opcodes.ACC_PUBLIC
        private const val ABS_IFACE_METHOD = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT

        private fun iface(name: String, classSig: String?, interfaces: Array<String>? = null): ClassWriter {
            val cw = ClassWriter(0)
            cw.visit(V, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE, name, classSig, OBJ, interfaces)
            return cw
        }

        /** class Widget { Widget(Txt); Widget(Txt, int); } — two constructor arities. */
        private fun widget(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Widget", null, OBJ, null)
            cw.visitMethod(PUB, "<init>", "(Lgen/Txt;)V", null, null).visitEnd()
            cw.visitMethod(PUB, "<init>", "(Lgen/Txt;I)V", null, null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Num { Num(int); } — for a primitive-family argument-type check. */
        private fun num(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Num", null, OBJ, null)
            cw.visitMethod(PUB, "<init>", "(I)V", null, null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class View {} — the bound for [finder]'s generic `findViewById` (mirrors android.view.View). */
        private fun view(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/View", null, OBJ, null)
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Button extends View {} — a View subtype, the expected type in the reported case. */
        private fun button(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Button", null, "gen/View", null)
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Finder { public &lt;T extends View&gt; T findViewById(int); } — Android's `findViewById` shape:
         *  a bytecode method whose generic signature returns the type variable `T` bounded by `View`. */
        private fun finder(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Finder", null, OBJ, null)
            cw.visitMethod(PUB, "findViewById", "(I)Lgen/View;", "<T:Lgen/View;>(I)TT;", null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Panel { Panel$Params getParams(); Panel$SubParams getSub(); } — the ViewGroup/getLayoutParams
         *  shape: methods returning a NESTED type through the ERASED descriptor (no generic signature), so the
         *  reader must not leave the return FQN in `$`-nested form. */
        private fun panel(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Panel", null, OBJ, null)
            cw.visitMethod(PUB, "getParams", "()Lgen/Panel\$Params;", null, null).visitEnd()
            cw.visitMethod(PUB, "getSub", "()Lgen/Panel\$SubParams;", null, null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Panel$Params {} — a nested class (mirrors ViewGroup.LayoutParams). */
        private fun panelParams(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Panel\$Params", null, OBJ, null)
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Panel$SubParams extends Panel$Params {} — a nested subtype (mirrors a FrameLayout.LayoutParams). */
        private fun panelSubParams(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Panel\$SubParams", null, "gen/Panel\$Params", null)
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** class Txt { public Txt upper(); public java.lang.String name(); } */
        private fun txt(): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(V, PUB, "gen/Txt", null, OBJ, null)
            cw.visitMethod(PUB, "upper", "()Lgen/Txt;", null, null).visitEnd()
            cw.visitMethod(PUB, "name", "()Ljava/lang/String;", null, null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** interface Func<T, R> { R apply(T t); }  — a single-abstract-method (SAM) interface. */
        private fun func(): ByteArray {
            val cw = iface("gen/Func", "<T:L$OBJ;R:L$OBJ;>L$OBJ;")
            cw.visitMethod(ABS_IFACE_METHOD, "apply", "(L$OBJ;)L$OBJ;", "(TT;)TR;", null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** interface Coll<E> { Strm<E> stream(); } */
        private fun coll(): ByteArray {
            val cw = iface("gen/Coll", "<E:L$OBJ;>L$OBJ;")
            cw.visitMethod(ABS_IFACE_METHOD, "stream", "()Lgen/Strm;", "()Lgen/Strm<TE;>;", null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** interface Lst<E> extends Coll<E> { static <E> Lst<E> of(E e); } */
        private fun lst(): ByteArray {
            val cw = iface("gen/Lst", "<E:L$OBJ;>L$OBJ;Lgen/Coll<TE;>;", arrayOf("gen/Coll"))
            cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "of", "(L$OBJ;)Lgen/Lst;",
                "<E:L$OBJ;>(TE;)Lgen/Lst<TE;>;", null,
            ).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** interface Strm<X> { <R> Strm<R> map(Func<? super X, ? extends R> f); Lst<X> toList(); } */
        private fun strm(): ByteArray {
            val cw = iface("gen/Strm", "<X:L$OBJ;>L$OBJ;")
            cw.visitMethod(
                ABS_IFACE_METHOD, "map", "(Lgen/Func;)Lgen/Strm;",
                "<R:L$OBJ;>(Lgen/Func<-TX;+TR;>;)Lgen/Strm<TR;>;", null,
            ).visitEnd()
            cw.visitMethod(ABS_IFACE_METHOD, "toList", "()Lgen/Lst;", "()Lgen/Lst<TX;>;", null).visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }
    }
}
