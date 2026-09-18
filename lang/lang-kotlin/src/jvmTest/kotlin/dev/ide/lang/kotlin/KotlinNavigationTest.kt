package dev.ide.lang.kotlin

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source go-to navigation ([KotlinSourceAnalyzer.navigationTargets]): Declaration / Type Declaration / Super
 * resolve the symbol at the caret to a project-source location. Uses the shared analyzer + `tempProject`
 * harness over the stdlib jar; no index needed for these (Implementation needs a ready SubtypeIndex, verified
 * elsewhere). The caret is the `|` marker (stripped).
 */
class KotlinNavigationTest {

    private fun nav(file: String, code: String, kind: NavKind): List<NavTarget> {
        val caret = code.indexOf('|')
        require(caret >= 0) { "no caret marker '|' in code" }
        val clean = code.removeRange(caret, caret + 1)
        return analyzer.navigationTargets(DiskFile(srcDir.resolve(file)), clean, caret, kind)
    }

    @Test
    fun declarationJumpsToTheFunctionDeclaration() {
        val code = "package demo\nfun greet() {}\nfun caller() { gr|eet() }"
        val clean = code.replace("|", "")
        val targets = nav("Use.kt", code, NavKind.DECLARATION)
        assertEquals(1, targets.size, "one declaration target; got $targets")
        assertEquals(clean.indexOf("greet"), targets[0].offset, "points at the `greet` declaration")
    }

    /**
     * A member CALL on a receiver, which is the commonest go-to there is and was not covered.
     *
     * `x.foo` (a property) resolved and `foo()` (unqualified) resolved, but `x.foo()` did not: the name
     * reference's parent is the call expression, not the qualified expression, so the member branch never
     * ran and the scope branch cannot see a member.
     */
    @Test
    fun declarationJumpsToAMemberFunctionCalledOnAReceiver() {
        val code = "package demo\nclass Holder { fun render(): String = \"\" }\n" +
            "fun caller(h: Holder) { h.ren|der() }"
        val clean = code.replace("|", "")
        val targets = nav("UseMemberCall.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a called member resolves; got $targets")
        assertEquals(clean.indexOf("render"), targets[0].offset, "points at the `render` declaration")
    }

    /** The same shape for an EXTENSION function, which resolves through a different lookup. */
    @Test
    fun declarationJumpsToAnExtensionFunctionCalledOnAReceiver() {
        val code = "package demo\nclass Holder\nfun Holder.shout(): String = \"!\"\n" +
            "fun caller(h: Holder) { h.sho|ut() }"
        val clean = code.replace("|", "")
        val targets = nav("UseExtensionCall.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a called extension resolves; got $targets")
        assertEquals(clean.indexOf("shout"), targets[0].offset, "points at the `shout` declaration")
    }

    /**
     * An enum CONSTANT through the type now RESOLVES, but does not yet navigate — and that is asserted
     * rather than left to be discovered.
     *
     * Completion has listed these since it was written (`enumConstantsOf`); resolution never consulted it,
     * so the same offset that offered `LARGE` reported it unresolved. It resolves now (the analysis-parity
     * digest records it as `ENUM_CONSTANT LARGE`), but the symbol carries no declaration node and no owning
     * type FQN, so `declarationTargets` has nothing to turn into a location. Whoever gives it one should
     * see THIS test fail, and replace the emptiness below with the constant's own offset.
     */
    @Test
    fun anEnumConstantResolvesButHasNoNavigableDeclarationYet() {
        val code = "package demo\nenum class Kind { SMALL, LARGE }\nfun caller() { val k = Kind.LA|RGE }"
        val targets = nav("UseEnumConstant.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isEmpty(), "an enum constant has no navigable declaration yet; got $targets")
    }

    /** A COMPANION member reached through the type, for the same reason. */
    @Test
    fun declarationJumpsToACompanionFunction() {
        val code = "package demo\nclass Counter { companion object { fun zero(): Int = 0 } }\n" +
            "fun caller() { val c = Counter.ze|ro() }"
        val clean = code.replace("|", "")
        val targets = nav("UseCompanion.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a companion member resolves; got $targets")
        assertEquals(clean.indexOf("fun zero") + 4, targets[0].offset, "points at the `zero` declaration")
    }

    /**
     * A name in a TYPE position denotes the type, even when something in scope answers to it too.
     *
     * `String` has a `String(chars)` factory function in the stdlib, and asking the scope first resolved the
     * return type of `fun f(): String` to THAT -- so hover and go-to on an ordinary type annotation landed
     * on a function.
     */
    @Test
    fun aNameInTypePositionResolvesToTheTypeNotASameNamedFunction() {
        val code = "package demo\nclass Holder\nfun Holder(seed: Int): Holder = Holder()\n" +
            "fun caller(): Hol|der = Holder(1)"
        val clean = code.replace("|", "")
        val targets = nav("UseTypePosition.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a type reference resolves; got $targets")
        assertEquals(clean.indexOf("class Holder") + 6, targets[0].offset, "points at the CLASS, not the factory")
    }

    @Test
    fun declarationJumpsToALocalVal() {
        val code = "package demo\nfun caller() { val name = 1\nprintln(na|me) }"
        val clean = code.replace("|", "")
        val targets = nav("Use2.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a local val resolves; got $targets")
        assertEquals(clean.indexOf("name"), targets[0].offset, "points at the `name` declaration")
    }

    @Test
    fun typeDeclarationJumpsToTheType() {
        // `item: Foo` — Type Declaration jumps to Foo's declaration (a fixture class, on disk).
        val code = "package demo\nfun caller(item: Foo) { it|em.hashCode() }"
        val targets = nav("Use3.kt", code, NavKind.TYPE_DECLARATION)
        assertTrue(targets.any { it.file.name == "Lib.kt" }, "jumps to Foo in Lib.kt; got $targets")
    }

    @Test
    fun superJumpsToTheSupertype() {
        // Caret on the subclass name → its supertype's source declaration.
        val code = "package demo\nclass Su|b : Base"
        val targets = nav("Use4.kt", code, NavKind.SUPER)
        assertTrue(targets.any { it.file.name == "Lib.kt" }, "jumps to Base in Lib.kt; got $targets")
    }

    @Test
    fun declarationJumpsToForwardTopLevelBackingProperty() {
        // Caret on a `_edit` READ inside `edit`'s getter → jump to the top-level `_edit` declared BELOW it
        // (the Compose ImageVector backing-property pattern; top-level decls are order-independent).
        val code = "package demo\n" +
            "val edit: Int\n" +
            "  get() {\n" +
            "    if (_e|dit != null) return _edit!!\n" +
            "    return _edit!!\n" +
            "  }\n" +
            "private var _edit: Int? = null\n"
        val clean = code.replace("|", "")
        val targets = nav("Edit.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "the forward top-level backing property must navigate; got $targets")
        assertEquals(clean.indexOf("_edit: Int?"), targets[0].offset, "points at the `_edit` declaration")
    }

    @Test
    fun nothingResolvesToNoTargets() {
        val code = "package demo\nfun caller() { val x = 1|2 }"
        assertTrue(nav("Use5.kt", code, NavKind.DECLARATION).isEmpty(), "a numeric literal has no declaration")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Lib.kt" to "package demo\nclass Foo\ninterface Base { fun f() }\n"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
