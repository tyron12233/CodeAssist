package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import org.jetbrains.kotlin.psi.KtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Directly exercises [IncrementalDecls.plan] to prove the dependency scoping narrows the recompute set (the
 * end-to-end correctness is covered by KotlinIncrementalAnalyzeTest's scoped-equals-full assertions; these
 * assert that an unrelated declaration is genuinely NOT recomputed).
 */
class IncrementalDeclsPlanTest {

    private fun parse(text: String): KtFile = KotlinParserHost.parse("Plan.kt", text)

    private fun plan(v1: String, v2: String): IncrementalDecls.Plan {
        val f1 = parse(v1)
        val f2 = parse(v2)
        return IncrementalDecls.plan(
            prev = f1.declarations.map { IncrementalDecls.factsOf(it) },
            prevImports = IncrementalDecls.importsOf(f1),
            prevFileText = v1,
            topDecls = f2.declarations,
            curImports = IncrementalDecls.importsOf(f2),
            curFileText = v2,
        )
    }

    @Test
    fun signatureChangeScopesToDependentsOnly() {
        val v1 = "package demo\nfun helper() {}\nfun caller() { helper() }\nfun unrelated() { println(\"x\") }\n"
        val v2 = v1.replace("fun helper() {}", "fun helper(x: Int) {}")
        val p = plan(v1, v2)
        assertTrue(p is IncrementalDecls.Plan.Partial, "a scoped plan, not a full recompute")
        // helper (0) changed; caller (1) references helper → dependent; unrelated (2) reused.
        assertEquals(setOf(0, 1), p.recompute)
    }

    @Test
    fun bodyOnlyEditScopesToItselfWithFineReuse() {
        val v1 = "package demo\nfun a() { val x = 1 }\nfun b() { val y = 2 }\n"
        val v2 = v1.replace("val x = 1", "val x = 99999")
        val p = plan(v1, v2) as IncrementalDecls.Plan.Partial
        assertEquals(setOf(0), p.recompute) // only a() recomputes
        assertEquals(0, p.fineReuse)        // and is eligible for intra-function statement reuse
    }

    @Test
    fun deletionInsideADeclarationIsDetected() {
        val v1 = "package demo\nfun a() {\n    val x = 1\n    x.hashCode()\n}\nfun b() { println(1) }\n"
        val v2 = v1.replace("    x.hashCode()\n", "") // pure deletion collapses the new-text span to a point
        val p = plan(v1, v2) as IncrementalDecls.Plan.Partial
        assertEquals(setOf(0), p.recompute) // a() (which lost a statement) is recomputed, not reused stale
    }

    @Test
    fun operatorSignatureChangeFallsBackToFull() {
        val v1 = "package demo\nclass V { operator fun plus(o: V): V = this }\nfun use(a: V, b: V) = a + b\n"
        val v2 = v1.replace("operator fun plus(o: V): V", "operator fun plus(o: Int): V")
        // A symbolic-operator caller (`a + b`) carries no `plus` name reference, so the change can't be scoped.
        assertTrue(plan(v1, v2) is IncrementalDecls.Plan.Full)
    }

    // ---- class keys: a method-body keystroke must NOT read as a class signature change ----

    private val cls = "package demo\n" +
        "class View {\n" +
        "    private val label: String = \"hi\"\n" +
        "    init { println(label) }\n" +
        "    fun alpha() { val x = 1 }\n" +
        "    val sized: Int get() { return 7 }\n" +
        "    constructor() { println(\"ctor\") }\n" +
        "}\n" +
        "fun outside() { View().alpha() }\n"

    @Test
    fun methodBodyEditInsideAClassIsABodyOnlyChange() {
        val p = plan(cls, cls.replace("val x = 1", "val x = 42")) as IncrementalDecls.Plan.Partial
        // Only the class recomputes — `outside` references `View`/`alpha` but neither signature moved.
        assertEquals(setOf(0), p.recompute)
        assertEquals(0, p.fineReuse) // and is eligible for per-member reuse
    }

    @Test
    fun initBlockAndSecondaryConstructorBodiesAreBodyOnlyToo() {
        for (v2 in listOf(
            cls.replace("println(label)", "println(label.length)"),
            cls.replace("println(\"ctor\")", "println(\"built\")"),
            cls.replace("return 7", "return 8"),
        )) {
            val p = plan(cls, v2) as IncrementalDecls.Plan.Partial
            assertEquals(setOf(0), p.recompute, "body edit in <$v2>")
            assertEquals(0, p.fineReuse, "body edit in <$v2>")
        }
    }

    @Test
    fun memberSignatureEditInsideAClassFiresDependents() {
        val p = plan(cls, cls.replace("fun alpha()", "fun alpha(seed: Int)")) as IncrementalDecls.Plan.Partial
        // A member's signature IS the class's signature: `outside` calls alpha() and must re-check.
        assertEquals(setOf(0, 1), p.recompute)
        assertEquals(null, p.fineReuse) // a signature change disables sub-declaration reuse
    }

    @Test
    fun propertyInitializerEditInsideAClassFiresDependents() {
        // An initializer types the property, so it is signature, not body — even though it sits in the body.
        val p = plan(cls, cls.replace("val label: String = \"hi\"", "val label: String = \"bye\"")) as IncrementalDecls.Plan.Partial
        assertEquals(null, p.fineReuse)
    }

    @Test
    fun anUntypedPropertysAccessorBodyStaysInTheHeader() {
        // `val n get() { … }` would take its type FROM the accessor body, so that body must NOT be cut from
        // the header the way a typed accessor's is.
        val src = "package demo\nclass C {\n    val n get() { return 1 }\n}\n"
        val k1 = IncrementalDecls.keyOf(parse(src).declarations[0])
        val k2 = IncrementalDecls.keyOf(parse(src.replace("return 1", "return \"s\"")).declarations[0])
        assertTrue(k1.header != k2.header, "an untyped accessor's body is part of the class signature")
    }

    @Test
    fun noTextChangeReusesEverything() {
        val v1 = "package demo\nfun a() { val x = 1 }\nfun b() { val y = 2 }\n"
        val p = plan(v1, v1) as IncrementalDecls.Plan.Partial
        assertTrue(p.recompute.isEmpty(), "an unchanged re-run reuses every declaration")
    }
}
