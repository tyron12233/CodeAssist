package dev.ide.kotlin.syntax

import org.jetbrains.kotlin.kmp.lexer.KtTokens
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.kotlin.syntax.psi.findElementAt
import dev.ide.kotlin.syntax.psi.getParentOfType
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `Kt*` facade: the accessors the editor backend actually calls, over the VENDORED grammar.
 *
 * The parser is the compiler's now, so this suite is no longer about whether the tree is right — the parity
 * suite answers that against 774 of the compiler's own corpus files. What is left to check here is the
 * bridge: that reading a `LightSyntaxTree` through PSI-shaped accessors gives PSI-shaped answers.
 *
 * The names are the compiler's, deliberately: a call site written against `org.jetbrains.kotlin.psi` should
 * need an import change and little else.
 */
class KtPsiFacadeTest {

    private fun file(text: String) = KotlinSyntax.parseFile(text)

    // --- identity ----------------------------------------------------------------------------------------

    @Test
    fun wrappersAreCreatedOncePerNode() {
        // The analysis engine keys identity maps on elements, so a facade that minted a fresh wrapper per
        // access would turn every such map into a leak that still passed its own tests.
        val parsed = file("class Foo")
        assertSame(parsed.declarations.single(), parsed.declarations.single())
        assertSame(parsed, parsed.declarations.single().parent)
    }

    @Test
    fun anUnknownElementIsStillAnElement() {
        // The tree is the authority; a node the facade has no name for keeps its range, text and children.
        val parsed = file("package a.b")
        val directive = parsed.packageDirective
        assertNotNull(directive)
        assertTrue(directive.children.isNotEmpty())
    }

    // --- file --------------------------------------------------------------------------------------------

    @Test
    fun fileStructure() {
        val parsed = file("package a.b\n\nimport c.D\nimport e.F as G\n\nclass H\nfun i() {}\nval j = 1")
        assertEquals("a.b", parsed.packageFqName.asString())
        assertEquals(listOf("c.D", "e.F"), parsed.importDirectives.map { it.importedFqName?.asString() })
        assertEquals(listOf("D", "G"), parsed.importDirectives.map { it.aliasName })
        assertEquals(3, parsed.declarations.size)
    }

    @Test
    fun theDefaultPackageIsRootRatherThanNull() {
        val packageName = file("class Foo").packageFqName
        assertTrue(packageName.isRoot)
        assertEquals("", packageName.asString())
    }

    // --- classifiers -------------------------------------------------------------------------------------

    @Test
    fun classShape() {
        val cls = file("data class Point(val x: Int, var y: Int = 0) : Base(), Marker { fun f() {} }")
            .declarations.single() as KtClass
        assertEquals("Point", cls.name)
        assertTrue(cls.isData())
        assertFalse(cls.isInterface())
        assertEquals(listOf("x", "y"), cls.primaryConstructor?.valueParameters?.map { it.name })
        assertTrue(cls.primaryConstructor!!.valueParameters[0].hasValOrVar())
        assertFalse(cls.primaryConstructor!!.valueParameters[0].isMutable)
        assertTrue(cls.primaryConstructor!!.valueParameters[1].isMutable)
        assertTrue(cls.primaryConstructor!!.valueParameters[1].hasDefaultValue())
        assertEquals(2, cls.superTypeListEntries.size)
        assertEquals(listOf("f"), cls.declarations.filterIsInstance<KtNamedFunction>().map { it.name })
    }

    @Test
    fun modifiersReadBackByToken() {
        val cls = file("private sealed class Foo").declarations.single() as KtClass
        assertTrue(cls.hasModifier(KtTokens.PRIVATE_MODIFIER))
        assertTrue(cls.hasModifier(KtTokens.SEALED_MODIFIER))
        assertFalse(cls.hasModifier(KtTokens.PUBLIC_MODIFIER))
        // The vendored vocabulary prints a keyword as its text, which is what a reader wants to see anyway.
        assertEquals(listOf("private", "sealed"), cls.modifierList?.modifiers?.map { it.toString() })
    }

    @Test
    fun interfacesEnumsAndCompanions() {
        assertTrue((file("interface Foo").declarations.single() as KtClass).isInterface())

        val enum = file("enum class E { A, B }").declarations.single() as KtClass
        assertTrue(enum.isEnum())
        assertEquals(listOf("A", "B"), enum.enumEntries.map { it.name })

        val outer = file("class A { companion object Named { val x = 1 } }").declarations.single() as KtClass
        val companion = outer.declarations.filterIsInstance<KtObjectDeclaration>().single()
        assertTrue(companion.isCompanion())
        assertEquals("Named", companion.name)
    }

    @Test
    fun annotationsAreReadableByShortName() {
        val fn = file("@Deprecated(\"why\")\n@field:JvmStatic\nfun f() {}").declarations.single() as KtNamedFunction
        assertEquals(listOf("Deprecated", "JvmStatic"), fn.annotationEntries.map { it.shortName?.asString() })
        assertEquals("field", fn.annotationEntries[1].useSiteTarget)
        assertEquals(1, fn.annotationEntries[0].valueArguments.size)
    }

    // --- callables ---------------------------------------------------------------------------------------

    @Test
    fun functionShape() {
        val fn = file("suspend fun <T> List<T>.transform(n: Int, f: (T) -> T): List<T> = this")
            .declarations.single() as KtNamedFunction
        assertEquals("transform", fn.name)
        assertTrue(fn.hasModifier(KtTokens.SUSPEND_MODIFIER))
        assertEquals(listOf("T"), fn.typeParameters.map { it.name })
        assertEquals("List<T>", fn.receiverTypeReference?.text)
        assertEquals(listOf("n", "f"), fn.valueParameters.map { it.name })
        assertEquals("List<T>", fn.typeReference?.text)
        assertFalse(fn.hasBlockBody())
    }

    @Test
    fun aFunctionWithNoReceiverReportsNone() {
        val fn = file("fun transform(): Int = 1").declarations.single() as KtNamedFunction
        assertNull(fn.receiverTypeReference)
        assertEquals("Int", fn.typeReference?.text)
    }

    @Test
    fun propertyShape() {
        val property = file("val a: Int = 1").declarations.single() as KtProperty
        assertEquals("a", property.name)
        assertFalse(property.isVar)
        assertEquals("Int", property.typeReference?.text)
        assertEquals("1", property.initializer?.text)

        val delegated = file("val b by lazy { 1 }").declarations.single() as KtProperty
        assertTrue(delegated.hasDelegate())
        assertNull(delegated.initializer)

        val withAccessors = file("var c: Int = 0\n    get() = field\n    private set")
            .declarations.single() as KtProperty
        assertTrue(withAccessors.isVar)
        assertNotNull(withAccessors.getter)
        assertNotNull(withAccessors.setter)
        assertTrue(withAccessors.setter!!.hasModifier(KtTokens.PRIVATE_MODIFIER))
    }

    @Test
    fun localityIsAnswerableFromTheTree() {
        val local = file("fun f() { val inner = 1 }")
            .collectDescendantsOfType<KtProperty>().single()
        assertTrue(local.isLocal)
        assertFalse((file("val top = 1").declarations.single() as KtProperty).isLocal)
    }

    // --- expressions -------------------------------------------------------------------------------------

    @Test
    fun qualifiedCallsReadAsReceiverAndSelector() {
        val qualified = file("val x = foo.bar(1)")
            .collectDescendantsOfType<KtDotQualifiedExpression>().single()
        assertEquals("foo", (qualified.receiverExpression as KtNameReferenceExpression).getReferencedName())
        val call = qualified.selectorExpression as KtCallExpression
        assertEquals("bar", (call.calleeExpression as KtNameReferenceExpression).getReferencedName())
        assertEquals(1, call.valueArguments.size)
        assertEquals("1", call.valueArguments.single().getArgumentExpression()?.text)
    }

    @Test
    fun namedAndSpreadArguments() {
        val call = file("val x = f(a = 1, *rest)").collectDescendantsOfType<KtCallExpression>().single()
        assertEquals("a", call.valueArguments[0].getArgumentName()?.asName?.identifier)
        assertNull(call.valueArguments[1].getArgumentName())
        assertTrue(call.valueArguments[1].isSpread)
    }

    @Test
    fun binaryExpressionsExposeBothSides() {
        val binary = file("val x = a + b").collectDescendantsOfType<KtBinaryExpression>().single()
        assertEquals("a", binary.left?.text)
        assertEquals("b", binary.right?.text)
        assertSame(KtTokens.PLUS, binary.operationToken)
    }

    @Test
    fun trailingLambdasAreArgumentsOfTheirCall() {
        val call = file("val x = list.map { it * 2 }").collectDescendantsOfType<KtCallExpression>().single()
        assertEquals(1, call.lambdaArguments.size)

        // valueArguments INCLUDES the trailing lambda, as upstream. The obvious reading is "what is in the
        // parentheses", and an arity check written against that counts `map { }` as a call with no
        // arguments, which is wrong on most Kotlin ever written.
        assertEquals(1, call.valueArguments.size)
        assertTrue(call.valueArguments.single() is KtLambdaArgument)

        val lambda = call.lambdaArguments.single().getLambdaExpression()
        assertNotNull(lambda)
        assertFalse(lambda.functionLiteral!!.hasParameterSpecification)
    }

    @Test
    fun lambdaParametersWhenDeclared() {
        val lambda = file("val x = fold(0) { acc, e -> acc + e }")
            .collectDescendantsOfType<KtLambdaExpression>().single()
        assertEquals(listOf("acc", "e"), lambda.valueParameters.map { it.name })
        assertNotNull(lambda.bodyExpression)
    }

    @Test
    fun ifAndWhenBranches() {
        val branch = file("val x = if (a) b else c").collectDescendantsOfType<KtIfExpression>().single()
        assertEquals("a", branch.condition?.text)
        assertEquals("b", branch.then?.text)
        assertEquals("c", branch.`else`?.text)

        val choice = file("val x = when (v) { 1 -> a; else -> b }")
            .collectDescendantsOfType<KtWhenExpression>().single()
        assertEquals("v", choice.subjectExpression?.text)
        assertEquals(2, choice.entries.size)
        assertTrue(choice.entries.last().isElse)
        assertEquals("b", choice.elseExpression?.text)
    }

    @Test
    fun stringTemplatesSeparatePlainFromInterpolated() {
        assertTrue(
            file("val x = \"plain\"").collectDescendantsOfType<KtStringTemplateExpression>().single().isPlain,
        )
        val interpolated = file("val x = \"a \${b.c} d\"")
            .collectDescendantsOfType<KtStringTemplateExpression>().single()
        assertFalse(interpolated.isPlain)
        assertEquals(1, interpolated.collectDescendantsOfType<KtDotQualifiedExpression>().size)
    }

    // --- tree walking ------------------------------------------------------------------------------------

    @Test
    fun parentLookupFindsTheNearestEnclosingElement() {
        val reference = file("fun f() { val x = y }")
            .collectDescendantsOfType<KtNameReferenceExpression>().single { it.getReferencedName() == "y" }
        assertNotNull(reference.getParentOfType<KtBlockExpression>())
        assertEquals("f", reference.getParentOfType<KtNamedFunction>()?.name)
    }

    @Test
    fun strictParentLookupSkipsTheElementItself() {
        val fn = file("fun f() {}").declarations.single() as KtNamedFunction
        assertSame(fn, fn.getParentOfType<KtNamedFunction>())
        assertNull(fn.getStrictParentOfType<KtNamedFunction>())
    }

    @Test
    fun findElementAtResolvesACaretPosition() {
        val text = "fun f() { val name = 1 }"
        val parsed = file(text)
        val element = parsed.findElementAt(text.indexOf("name") + 2)
        assertNotNull(element)
        assertEquals("name", element.getParentOfType<KtProperty>()?.name)
    }

    @Test
    fun everyElementKnowsItsFile() {
        val parsed = file("class A { fun b() {} }")
        val nested: KtElement = parsed.collectDescendantsOfType<KtNamedFunction>().single()
        assertSame(parsed, nested.containingKtFile)
    }

    @Test
    fun rangesPointAtTheRealText() {
        val text = "class Foo { fun bar() {} }"
        val fn = file(text).collectDescendantsOfType<KtNamedFunction>().single()
        assertEquals("fun bar() {}", fn.text)
        assertEquals(text.indexOf("fun bar"), fn.textOffset)
        assertEquals(text.substring(fn.textRange.startOffset, fn.textRange.endOffset), fn.text)
    }

    @Test
    fun docCommentsAttachToTheDeclarationBelowThem() {
        val fn = file("/** Explains f. */\nfun f() {}").declarations.single() as KtNamedFunction
        assertEquals("/** Explains f. */", fn.docComment?.text)
        assertNull((file("fun g() {}").declarations.single() as KtNamedFunction).docComment)
    }
}
