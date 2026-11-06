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
     * References that RESOLVE but have nowhere to navigate to — asserted, not left to be discovered.
     *
     * Each of these was unresolved until the analysis-parity digest surfaced it, and each resolves now (the
     * digest records the kind). What none of them has is a location: an enum constant and a project
     * typealias carry no declaration node and no owning type, and a type parameter is not represented in the
     * neutral DOM at all — `nodeAt` walks past it to the file, so a target built from it would send
     * Ctrl-click to line 1, which is worse than going nowhere.
     *
     * Whoever gives one of them a location should see THIS fail, and replace its entry with the offset.
     */
    @Test
    fun theseResolveButHaveNoNavigableDeclarationYet() {
        val cases = listOf(
            "an enum constant" to
                "package demo\nenum class Kind { SMALL, LARGE }\nfun caller() { val k = Kind.LA|RGE }",
            "a type parameter" to
                "package demo\nclass Box<T> { fun first(items: List<|T>): T = items[0] }",
            "a project typealias" to
                "package demo\nclass Shape\ntypealias Table = Map<String, Shape>\nfun caller(): Tab|le? = null",
        )
        for ((what, code) in cases) {
            val targets = nav("UseNoNav.kt", code, NavKind.DECLARATION)
            assertTrue(targets.isEmpty(), "$what has no navigable declaration yet; got $targets")
        }
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

    // --- Go to Implementation on a member: the overriding members, at every level ---

    private val shapes = SHAPES

    private fun implementations(file: String, code: String) = nav(file, code, NavKind.IMPLEMENTATION).map { it.label }

    @Test
    fun implementationOfAMemberDeclarationListsItsOverrides() {
        val code = shapes.replaceFirst("abstract fun area", "abstract fun ar|ea")
        assertEquals(
            listOf("area  ·  Circle", "area  ·  Ring", "area  ·  Square"),
            implementations("Shapes.kt", code),
            "every override, the grandchild Ring included, not the classes",
        )
    }

    @Test
    fun implementationOfACallListsTheOverridesOfWhatItCalls() {
        val code = "package demo\nfun total(s: Shape) = s.ar|ea()"
        assertEquals(listOf("area  ·  Circle", "area  ·  Ring", "area  ·  Square"), implementations("UseShapes.kt", code))
    }

    @Test
    fun implementationOfAPropertyListsOnlyTheTypesThatOverrideIt() {
        val code = shapes.replaceFirst("open val name", "open val na|me")
        assertEquals(listOf("name  ·  Square"), implementations("Shapes.kt", code))
    }

    @Test
    fun aMemberNothingOverridesHasNoImplementations() {
        val code = shapes.replaceFirst("fun fixed", "fun fix|ed")
        assertEquals(emptyList(), implementations("Shapes.kt", code), "not the class's subclasses instead")
    }

    @Test
    fun implementationOfAnOverloadListsOnlyItsOwnOverrides() {
        val ofString = SHAPES.replaceFirst("open fun print(s", "open fun pri|nt(s")
        assertEquals(listOf("print  ·  Fancy"), implementations("Shapes.kt", ofString))
        val ofInt = SHAPES.replaceFirst("open fun print(x", "open fun pri|nt(x")
        assertEquals(emptyList(), implementations("Shapes.kt", ofInt), "Fancy overrides the String overload only")
    }

    @Test
    fun aGenericMemberIsOverriddenWithTheConcreteType() {
        val code = SHAPES.replaceFirst("abstract fun put", "abstract fun pu|t")
        assertEquals(listOf("put  ·  StrBox"), implementations("Shapes.kt", code), "`put(x: String)` overrides `put(x: T)`")
    }

    @Test
    fun implementationOfATypeStillListsItsSubclasses() {
        val code = shapes.replaceFirst("abstract class Shape", "abstract class Sha|pe")
        val labels = implementations("Shapes.kt", code)
        assertTrue(labels.any { it.startsWith("Circle") } && labels.any { it.startsWith("Square") }, "got $labels")
    }

    companion object {
        private val SHAPES = "package demo\n" +
            "abstract class Shape { abstract fun area(): Double; open val name: String = \"shape\"; fun fixed() = 1 }\n" +
            "open class Circle : Shape() { override fun area() = 1.0 }\n" +
            "class Ring : Circle() { override fun area() = 2.0 }\n" +
            "class Square : Shape() { override fun area() = 4.0; override val name = \"sq\" }\n" +
            "open class Printer { open fun print(x: Int) {}; open fun print(s: String) {} }\n" +
            "class Fancy : Printer() { override fun print(s: String) {} }\n" +
            "abstract class Box<T> { abstract fun put(x: T) }\n" +
            "class StrBox : Box<String>() { override fun put(x: String) {} }\n"

        val srcDir: Path = tempProject(
            mapOf("Lib.kt" to "package demo\nclass Foo\ninterface Base { fun f() }\n", "Shapes.kt" to SHAPES),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
