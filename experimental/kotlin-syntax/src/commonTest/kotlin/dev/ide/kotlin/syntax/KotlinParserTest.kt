package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.parsing.KotlinParser
import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared entry point, so every suite parses the same way. */
internal object KotlinParserFixture {
    fun parse(text: String): AstNode = KotlinParser.parse(text)
}

/**
 * The parser, by tree shape.
 *
 * The assertions are on the rendered tree rather than on accessors, because the shape IS the contract: it is
 * what the differential oracle compares against the real compiler, and what the facade reads. Reading these
 * as expected strings also makes a regression legible — the diff shows the node that moved.
 *
 * Two properties are checked on every input here, not just the interesting ones: the tree covers the whole
 * file, and correct code produces no error elements. The second is the one that matters in practice, since a
 * parser that over-reports errors is worse than useless in an editor.
 */
class KotlinParserTest {

    private fun tree(text: String): String {
        val root = KotlinParserFixture.parse(text)
        assertCovers(text, root)
        return root.treeString()
    }

    private fun assertCovers(text: String, root: AstNode) {
        val rebuilt = root.descendants().filter { it.children.isEmpty() }.joinToString("") { it.text }
        assertEquals(text, rebuilt, "the tree must reproduce the source exactly")
    }

    private fun assertNoErrors(text: String) {
        val root = KotlinParserFixture.parse(text)
        assertCovers(text, root)
        val errors = root.descendants().filter { it.elementType === TokenType.ERROR_ELEMENT }.toList()
        assertTrue(errors.isEmpty(), "valid code produced ${errors.size} error(s) in ${root.treeString()}")
    }

    // --- file structure ----------------------------------------------------------------------------------

    @Test
    fun emptyFileStillHasAPackageDirectiveAndImportList() {
        // The compiler always gives a file both, empty; downstream code reads them unconditionally.
        assertEquals("(kotlin.FILE (PACKAGE_DIRECTIVE) (IMPORT_LIST))", tree(""))
    }

    @Test
    fun packageAndImports() {
        assertEquals(
            "(kotlin.FILE (PACKAGE_DIRECTIVE PACKAGE_KEYWORD (DOT_QUALIFIED_EXPRESSION " +
                "(REFERENCE_EXPRESSION IDENTIFIER) DOT (REFERENCE_EXPRESSION IDENTIFIER))) " +
                "(IMPORT_LIST (IMPORT_DIRECTIVE IMPORT_KEYWORD (DOT_QUALIFIED_EXPRESSION " +
                "(REFERENCE_EXPRESSION IDENTIFIER) DOT (REFERENCE_EXPRESSION IDENTIFIER)))))",
            tree("package a.b\nimport c.D"),
        )
    }

    @Test
    fun importForms() {
        assertNoErrors("import a.b.*")
        assertNoErrors("import a.b.C as D")
        val aliased = KotlinParser.parseFile("import a.b.C as D")
        assertEquals("D", aliased.importDirectives.single().aliasName)
        assertTrue(KotlinParser.parseFile("import a.b.*").importDirectives.single().isAllUnder)
    }

    @Test
    fun fileAnnotations() {
        assertNoErrors("@file:JvmName(\"Foo\")\npackage a")
    }

    // --- declarations ------------------------------------------------------------------------------------

    @Test
    fun simpleClass() {
        assertEquals(
            "(kotlin.FILE (PACKAGE_DIRECTIVE) (IMPORT_LIST) (CLASS CLASS_KEYWORD IDENTIFIER))",
            tree("class Foo"),
        )
    }

    @Test
    fun softKeywordModifiersArePromoted() {
        // `data` reached the parser as IDENTIFIER; the tree has to record what it turned out to be.
        assertTrue(tree("data class Foo").contains("(MODIFIER_LIST DATA_KEYWORD)"))
        assertTrue(tree("value class Foo").contains("(MODIFIER_LIST VALUE_KEYWORD)"))
        assertTrue(tree("private suspend fun f() {}").contains("(MODIFIER_LIST PRIVATE_KEYWORD SUSPEND_KEYWORD)"))
    }

    @Test
    fun aSoftKeywordUsedAsANameStaysAnIdentifier() {
        // The mirror case, and the one that a naive promotion breaks.
        val rendered = tree("val data = 1")
        assertFalse(rendered.contains("DATA_KEYWORD"), "`data` here is a variable name: $rendered")
    }

    @Test
    fun classWithPrimaryConstructorAndSupertypes() {
        assertNoErrors("class Foo(val a: Int, b: String = \"\") : Bar(a), Baz")
        val file = KotlinParser.parseFile("class Foo(val a: Int, b: String) : Bar(a), Baz")
        val cls = file.declarations.single() as dev.ide.kotlin.syntax.psi.KtClass
        assertEquals(listOf("a", "b"), cls.primaryConstructor?.valueParameters?.map { it.name })
        assertEquals(2, cls.superTypeListEntries.size)
    }

    @Test
    fun genericsAndConstraints() {
        assertNoErrors("class Box<out T : Any>(val value: T)")
        assertNoErrors("fun <T> foo(t: T): T where T : Comparable<T>, T : Any = t")
    }

    @Test
    fun interfacesObjectsAndCompanions() {
        assertNoErrors("interface Foo { fun bar() }")
        assertNoErrors("object Singleton { val x = 1 }")
        assertNoErrors("class A { companion object { const val X = 1 } }")
    }

    @Test
    fun enumEntries() {
        assertNoErrors("enum class Color(val rgb: Int) { RED(0xFF0000), GREEN(0x00FF00); fun f() {} }")
        val file = KotlinParser.parseFile("enum class Color { RED, GREEN, BLUE }")
        val cls = file.declarations.single() as dev.ide.kotlin.syntax.psi.KtClass
        assertTrue(cls.isEnum)
        assertEquals(listOf("RED", "GREEN", "BLUE"), cls.enumEntries.map { it.name })
    }

    @Test
    fun functionForms() {
        assertNoErrors("fun f() {}")
        assertNoErrors("fun f(): Int = 1")
        assertNoErrors("fun List<Int>.sum2(): Int = 0")
        assertNoErrors("fun f(vararg xs: Int, block: (Int) -> Unit) {}")
        assertNoErrors("suspend fun f() {}")
    }

    @Test
    fun extensionReceiverIsDistinguishedFromTheName() {
        // `fun Foo.bar()` and `fun bar()` share a prefix; only the dot separates them.
        val extension = KotlinParser.parseFile("fun String.trimAll(): String = this")
            .declarations.single() as dev.ide.kotlin.syntax.psi.KtNamedFunction
        assertEquals("trimAll", extension.name)
        assertEquals("String", extension.receiverTypeReference?.text)

        val plain = KotlinParser.parseFile("fun trimAll(): String = \"\"")
            .declarations.single() as dev.ide.kotlin.syntax.psi.KtNamedFunction
        assertEquals("trimAll", plain.name)
        assertEquals(null, plain.receiverTypeReference)
    }

    @Test
    fun propertyForms() {
        assertNoErrors("val x = 1")
        assertNoErrors("var y: String? = null")
        assertNoErrors("val z by lazy { 1 }")
        assertNoErrors("val w: Int get() = 1")
        assertNoErrors("var v: Int = 0\n    private set")
        assertNoErrors("val (a, b) = pair")
    }

    @Test
    fun typeAliasAndInitBlock() {
        assertNoErrors("typealias Handler = (Int) -> Unit")
        assertNoErrors("class A { init { println(1) } }")
        assertNoErrors("class A { constructor(x: Int) : this() {} }")
    }

    // --- types -------------------------------------------------------------------------------------------

    @Test
    fun typeForms() {
        assertNoErrors("val a: List<Map<String, Int>> = f()")
        assertNoErrors("val b: String? = null")
        assertNoErrors("val c: (Int, String) -> Unit = {}")
        assertNoErrors("val d: Int.(String) -> Unit = {}")
        assertNoErrors("fun <T> f(t: T) where T : Any = t")
        assertNoErrors("val e: List<*> = f()")
    }

    @Test
    fun qualifiedTypesNestLeftToRight() {
        val rendered = tree("val x: a.b.C = f()")
        assertTrue(
            rendered.contains("(USER_TYPE (USER_TYPE (USER_TYPE (REFERENCE_EXPRESSION IDENTIFIER))"),
            "a qualified type nests: $rendered",
        )
    }

    @Test
    fun nullableWrapsRatherThanExtends() {
        assertTrue(tree("val x: Foo? = null").contains("(NULLABLE_TYPE (USER_TYPE"))
    }

    // --- expressions -------------------------------------------------------------------------------------

    @Test
    fun qualifiedCallShape() {
        // The call wraps only the selector, which is the shape every member resolution reads.
        assertTrue(
            tree("val x = foo.bar(1)").contains(
                "(DOT_QUALIFIED_EXPRESSION (REFERENCE_EXPRESSION IDENTIFIER) DOT " +
                    "(CALL_EXPRESSION (REFERENCE_EXPRESSION IDENTIFIER) (VALUE_ARGUMENT_LIST",
            ),
        )
    }

    @Test
    fun chainedCallsNestLeftToRight() {
        val rendered = tree("val x = a.b().c()")
        assertTrue(
            rendered.contains("(DOT_QUALIFIED_EXPRESSION (DOT_QUALIFIED_EXPRESSION (REFERENCE_EXPRESSION"),
            "chains are left-associative: $rendered",
        )
    }

    @Test
    fun arithmeticPrecedence() {
        // `*` binds tighter than `+`, so the multiplication is the inner node.
        val rendered = tree("val x = 1 + 2 * 3")
        assertTrue(
            rendered.contains("(BINARY_EXPRESSION (INTEGER_CONSTANT INTEGER_LITERAL) (OPERATION_REFERENCE PLUS) (BINARY_EXPRESSION"),
            rendered,
        )
    }

    @Test
    fun infixCallsBindTighterThanElvisAndLooserThanRange() {
        assertNoErrors("val x = a ?: b to c")
        assertNoErrors("val y = 1..10 step 2")
    }

    @Test
    fun lambdasAndTrailingLambdas() {
        assertNoErrors("val x = list.map { it * 2 }")
        assertNoErrors("val y = list.fold(0) { acc, e -> acc + e }")
        assertNoErrors("val z = run { 1 }")
        assertNoErrors("val w = f(1) { it }")
    }

    @Test
    fun aLambdaParameterListIsOnlyOneWhenTheArrowArrives() {
        // `{ a }` is a body, `{ a -> a }` is a parameter and a body, and the prefix is identical.
        assertFalse(tree("val x = run { a }").contains("VALUE_PARAMETER_LIST"))
        assertTrue(tree("val x = run { a -> a }").contains("VALUE_PARAMETER_LIST"))
    }

    @Test
    fun controlFlow() {
        assertNoErrors("fun f() { if (a) b else c }")
        assertNoErrors("fun f() { when (x) { 1 -> a; is Foo -> b; in 1..2 -> c; else -> d } }")
        assertNoErrors("fun f() { when { a -> b; else -> c } }")
        assertNoErrors("fun f() { for (i in 1..10) println(i) }")
        assertNoErrors("fun f() { while (a) { b() } }")
        assertNoErrors("fun f() { do { a() } while (b) }")
        assertNoErrors("fun f() { try { a() } catch (e: Exception) { b() } finally { c() } }")
    }

    @Test
    fun whenWithSubjectVariableAndGuard() {
        assertNoErrors("fun f() { when (val v = g()) { is Foo -> v; else -> null } }")
        assertNoErrors("fun f() { when (x) { is Foo if x.b -> 1; else -> 2 } }")
    }

    @Test
    fun jumpsAndLabels() {
        assertNoErrors("fun f() { loop@ for (i in 1..2) { break@loop } }")
        assertNoErrors("fun f() { list.forEach { return@forEach } }")
        assertNoErrors("fun f(): Int { return 1 }")
        assertNoErrors("fun f() { throw IllegalStateException() }")
    }

    @Test
    fun stringTemplatesExposeTheirInterpolations() {
        // Completion inside `"${…}"` depends on the interpolated expression being reachable.
        val rendered = tree("val x = \"a \${b.c} d\"")
        assertTrue(rendered.contains("(LONG_STRING_TEMPLATE_ENTRY"), rendered)
        assertTrue(rendered.contains("(DOT_QUALIFIED_EXPRESSION"), rendered)
        assertTrue(tree("val x = \"hi \$name\"").contains("(SHORT_STRING_TEMPLATE_ENTRY"))
    }

    @Test
    fun objectLiteralsAndCallableReferences() {
        assertNoErrors("val x = object : Runnable { override fun run() {} }")
        assertNoErrors("val y = String::length")
        assertNoErrors("val z = ::foo")
        assertNoErrors("val w = Foo::class")
    }

    @Test
    fun operatorsOverTypes() {
        assertNoErrors("val x = a as B")
        assertNoErrors("val y = a as? B")
        assertNoErrors("val z = a is B")
        assertNoErrors("val w = a !is B")
        assertNoErrors("val v = a!!")
        assertNoErrors("val u = a?.b?.c")
        assertNoErrors("val t = a[0]")
    }

    @Test
    fun nestedDeclarationsInsideBlocks() {
        assertNoErrors("fun f() { fun g() = 1; val x = g() }")
        assertNoErrors("fun f() { class Local; val x = Local() }")
    }

    // --- error tolerance ---------------------------------------------------------------------------------

    @Test
    fun brokenInputStillProducesAFullTree() {
        // Every one of these is something an editor sees mid-keystroke. None may throw or lose text.
        for (text in BROKEN_SOURCES) {
            val root = KotlinParserFixture.parse(text)
            assertCovers(text, root)
        }
    }

    @Test
    fun anUnclosedBraceDoesNotSwallowLaterDeclarations() {
        val text = "fun a() {\nfun b() {}\n"
        val root = KotlinParserFixture.parse(text)
        assertCovers(text, root)
        assertTrue(root.descendants().count { it.elementType === KtNodeTypes.FUN } >= 2)
    }

    @Test
    fun aMissingExpressionIsReportedWhereItIsMissing() {
        val root = KotlinParserFixture.parse("val x = ")
        val errors = root.descendants().filter { it.elementType === TokenType.ERROR_ELEMENT }.toList()
        assertTrue(errors.isNotEmpty(), "a missing initializer must be reported")
    }

    private companion object {
        val BROKEN_SOURCES = listOf(
            "class",
            "fun f(",
            "val x =",
            "class A { fun",
            "if (a) {",
            "val x = \"unterminated",
            "when (x) {",
            "fun f() { a. }",
            "import",
            "package",
            "@",
            "val x: ",
            "fun f(): ",
            "a b c d e",
            "}}}",
            "fun f() { val = 1 }",
        )
    }
}
