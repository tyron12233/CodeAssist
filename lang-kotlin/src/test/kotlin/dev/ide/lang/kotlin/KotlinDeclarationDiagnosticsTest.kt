package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Syntactic declaration-level diagnostics — checks that need no classpath resolution (so they fire in dumb
 * mode too): a `val`/`var` with no type and no initializer, `lateinit`/`abstract` modifier misuse, and a
 * `val`/`var` on a non-constructor parameter. Each error case must be flagged, each valid counterpart must not.
 */
class KotlinDeclarationDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun topLevelValWithNoTypeNoInitializerIsFlagged() {
        val diags = diagnose("NoInit.kt", "package demo\nval test")
        assertTrue(
            diags.any { it.code == "kt.noTypeNoInitializer" && it.message.contains("property") },
            "`val test` (no type, no initializer) should be flagged; got $diags",
        )
    }

    @Test
    fun localValWithNoTypeNoInitializerIsFlagged() {
        val diags = diagnose("LocalNoInit.kt", "package demo\nfun f() { val test }")
        assertTrue(
            diags.any { it.code == "kt.noTypeNoInitializer" && it.message.contains("variable") },
            "a local `val test` should be flagged; got $diags",
        )
    }

    @Test
    fun typedOrInitializedPropertyIsNotFlagged() {
        val diags = diagnose("Ok.kt", "package demo\nval a = 1\nval b: Int = 2\nval c: Int\n  get() = 3")
        assertTrue(
            diags.none { it.code == "kt.noTypeNoInitializer" },
            "an initialized / typed-with-getter property must not be flagged; got $diags",
        )
    }

    @Test
    fun lateinitOnValIsFlagged() {
        val diags = diagnose("LateVal.kt", "package demo\nlateinit val x: String")
        assertTrue(
            diags.any { it.code == "kt.lateinit" && it.message.contains("mutable") },
            "`lateinit val` should be flagged; got $diags",
        )
    }

    @Test
    fun lateinitWithInitializerIsFlagged() {
        val diags = diagnose("LateInit.kt", "package demo\nlateinit var x: String = \"\"")
        assertTrue(
            diags.any { it.code == "kt.lateinit" && it.message.contains("initializer") },
            "`lateinit var` with an initializer should be flagged; got $diags",
        )
    }

    @Test
    fun lateinitOnNullableTypeIsFlagged() {
        val diags = diagnose("LateNullable.kt", "package demo\nlateinit var x: String?")
        assertTrue(
            diags.any { it.code == "kt.lateinit" && it.message.contains("nullable") },
            "`lateinit var` of a nullable type should be flagged; got $diags",
        )
    }

    @Test
    fun validLateinitIsNotFlagged() {
        val diags = diagnose("LateOk.kt", "package demo\nlateinit var x: String")
        assertTrue(
            diags.none { it.code == "kt.lateinit" },
            "a valid `lateinit var x: String` must not be flagged; got $diags",
        )
    }

    @Test
    fun abstractFunctionWithBodyIsFlagged() {
        val diags = diagnose("AbsBody.kt", "package demo\nabstract class C { abstract fun f() {} }")
        assertTrue(
            diags.any { it.code == "kt.abstractModifier" && it.message.contains("body") },
            "an abstract function with a body should be flagged; got $diags",
        )
    }

    @Test
    fun abstractMemberInNonAbstractClassIsFlagged() {
        val diags = diagnose("AbsInPlain.kt", "package demo\nclass C { abstract fun f() }")
        assertTrue(
            diags.any { it.code == "kt.abstractModifier" && it.message.contains("non-abstract") },
            "an abstract member in a plain class should be flagged; got $diags",
        )
    }

    @Test
    fun abstractMemberInAbstractClassIsNotFlagged() {
        val diags = diagnose("AbsOk.kt", "package demo\nabstract class C { abstract fun f()\n  abstract val v: Int }")
        assertTrue(
            diags.none { it.code == "kt.abstractModifier" },
            "abstract members in an abstract class must not be flagged; got $diags",
        )
    }

    @Test
    fun abstractMemberInInterfaceIsNotFlagged() {
        val diags = diagnose("AbsIface.kt", "package demo\ninterface I { abstract fun f() }")
        assertTrue(
            diags.none { it.code == "kt.abstractModifier" },
            "an abstract member in an interface must not be flagged; got $diags",
        )
    }

    @Test
    fun valOnFunctionParameterIsFlagged() {
        val diags = diagnose("ValParam.kt", "package demo\nfun f(val x: Int) {}")
        assertTrue(
            diags.any { it.code == "kt.valVarParameter" && it.message.contains("function parameter") },
            "`val` on a function parameter should be flagged; got $diags",
        )
    }

    @Test
    fun valOnPrimaryConstructorParameterIsNotFlagged() {
        val diags = diagnose("CtorParam.kt", "package demo\nclass C(val x: Int, var y: String)")
        assertTrue(
            diags.none { it.code == "kt.valVarParameter" },
            "`val`/`var` on a primary-constructor parameter must not be flagged; got $diags",
        )
    }

    @Test
    fun incompatibleFinalOpenModifiersAreFlagged() {
        val diags = diagnose("FinalOpen.kt", "package demo\nopen class C { final open fun f() {} }")
        assertTrue(
            diags.any { it.code == "kt.modifiers" && it.message.contains("incompatible") },
            "`final open` should be flagged as incompatible; got $diags",
        )
    }

    @Test
    fun repeatedModifierIsFlagged() {
        val diags = diagnose("Repeated.kt", "package demo\nopen class C { open open fun f() {} }")
        assertTrue(
            diags.any { it.code == "kt.modifiers" && it.message.contains("Repeated") },
            "a repeated `open` should be flagged; got $diags",
        )
    }

    @Test
    fun multipleVisibilityModifiersAreFlagged() {
        val diags = diagnose("MultiVis.kt", "package demo\nprivate public val x = 1")
        assertTrue(
            diags.any { it.code == "kt.modifiers" && it.message.contains("visibility") },
            "two visibility modifiers should be flagged; got $diags",
        )
    }

    @Test
    fun validModifiersAreNotFlagged() {
        val diags = diagnose("ModOk.kt", "package demo\nabstract class C { protected abstract open fun f() }")
        assertTrue(
            diags.none { it.code == "kt.modifiers" },
            "compatible modifiers must not be flagged; got $diags",
        )
    }

    @Test
    fun destructuringEntryConflictingWithLocalValIsFlagged() {
        // The reported case: `a` is already a local `val`, then re-bound by a destructuring in the same block.
        val diags = diagnose(
            "Destr.kt",
            "package demo\nfun f() {\n" +
                "  val a = \"Hello\"\n" +
                "  println(a)\n" +
                "  val test = listOf(2 to 2)\n" +
                "  val (a, b) = test\n" +
                "}\n",
        )
        assertTrue(
            diags.count { it.code == "kt.conflictingDeclaration" && it.message.contains("'a'") } >= 2,
            "the redeclared `a` (val + destructuring entry) should both be flagged as conflicting; got $diags",
        )
        assertTrue(
            diags.none { it.code == "kt.conflictingDeclaration" && it.message.contains("'b'") },
            "`b` is declared once and must not be flagged; got $diags",
        )
    }

    @Test
    fun duplicateEntriesInOneDestructuringAreFlagged() {
        val diags = diagnose("DestrDup.kt", "package demo\nfun f() { val (x, x) = 1 to 2 }")
        assertTrue(
            diags.count { it.code == "kt.conflictingDeclaration" && it.message.contains("'x'") } >= 2,
            "`val (x, x)` should flag both entries as conflicting; got $diags",
        )
    }

    @Test
    fun distinctDestructuringAndIgnoreHoleAreNotFlagged() {
        // Distinct names never conflict; `_` is the ignore hole and two `_`s must not collide.
        val diags = diagnose("DestrOk.kt", "package demo\nfun f() {\n  val (a, b) = 1 to 2\n  val (_, d) = 3 to 4\n  val (_, e) = 5 to 6\n}\n")
        assertTrue(
            diags.none { it.code == "kt.conflictingDeclaration" },
            "distinct destructuring entries (and `_` holes) must not be flagged; got $diags",
        )
    }

    // --- function without a body must be abstract (2026-07-07) ---

    @Test
    fun concreteFunctionWithoutBodyIsFlagged() {
        val diags = diagnose("NoBody.kt", "package demo\nclass C {\n  fun foo()\n}")
        assertTrue(
            diags.any { it.code == "kt.functionNoBody" && it.message.contains("foo") },
            "a non-abstract `fun foo()` with no body should be flagged; got $diags",
        )
    }

    @Test
    fun abstractInterfaceAndBodiedFunctionsAreClean() {
        val diags = diagnose(
            "Bodies.kt",
            "package demo\n" +
                "interface I { fun a() }\n" +                       // interface member: implicitly abstract
                "abstract class B { abstract fun b(); fun c() {} }\n" + // abstract member + concrete member
                "external fun d()\n" +                              // external: body elsewhere
                "fun e() = 1\n",                                    // expression body
        )
        assertTrue(diags.none { it.code == "kt.functionNoBody" }, "valid body-less/bodied functions must not be flagged; got $diags")
    }

    // --- val ++/-- reassignment (2026-07-07) ---

    @Test
    fun incrementOfValIsFlagged() {
        val post = diagnose("Inc.kt", "package demo\nfun f() { val x = 0\n  x++ }")
        assertTrue(post.any { it.code == "kt.valReassign" }, "`x++` on a val should be flagged; got $post")
        val pre = diagnose("Inc2.kt", "package demo\nfun f() { val x = 0\n  --x }")
        assertTrue(pre.any { it.code == "kt.valReassign" }, "`--x` on a val should be flagged; got $pre")
    }

    @Test
    fun incrementOfVarIsClean() {
        val diags = diagnose("Inc3.kt", "package demo\nfun f() { var x = 0\n  x++\n  ++x }")
        assertTrue(diags.none { it.code == "kt.valReassign" }, "incrementing a var is fine; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
