package dev.ide.kotlin.syntax

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtAnnotation
import dev.ide.kotlin.syntax.psi.KtAnonymousInitializer
import dev.ide.kotlin.syntax.psi.KtBlockStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtConstructorCalleeExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDeclarationWithBody
import dev.ide.kotlin.syntax.psi.KtDoubleColonExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtFileAnnotationList
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionTypeReceiver
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtInstanceExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtLabelReferenceExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLiteralStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtModifierListOwner
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtSimpleNameExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntryWithExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtWhileExpressionBase
import dev.ide.kotlin.syntax.psi.ValueArgument
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import dev.ide.kotlin.syntax.psi.containingClassOrObject
import dev.ide.kotlin.syntax.psi.descendants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The abstract bases the facade grew for `:lang-kotlin`, and the node types that came with them.
 *
 * These exist so a call site written as `is KtFunction` or `parent as? ValueArgument` keeps working when
 * its import moves off `org.jetbrains.kotlin.psi`. That only holds if the interfaces reach the SAME set of
 * elements the compiler's do, and an interface reaching too much is as wrong as one reaching too little:
 * a `when` whose `is KtFunction` branch started matching class initializers would compile, pass every test
 * about functions, and silently change what the branch above it sees.
 */
class KtPsiInterfacesTest {

    private fun file(text: String) = KotlinSyntax.parseFile(text)

    private inline fun <reified T : Any> KtElement.all(): List<T> =
        descendants().filterIsInstance<T>().toList()

    // --- KtFunction / KtDeclarationWithBody --------------------------------------------------------------

    @Test
    fun everyKindOfFunctionIsAKtFunction() {
        // Four shapes upstream calls a function, and a lambda is one of them. A rule about parameters that
        // skipped lambdas would be quietly wrong wherever a lambda declares its own.
        val parsed = file(
            """
            class Foo(val a: Int) {
                constructor(b: String) : this(b.length)
                fun named(c: Int) = c
                val lambda = { d: Int -> d }
            }
            """.trimIndent(),
        )
        val functions = parsed.all<KtFunction>()
        assertEquals(
            listOf("KtPrimaryConstructor", "KtSecondaryConstructor", "KtNamedFunction", "KtFunctionLiteral"),
            functions.map { it::class.simpleName },
        )
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d")),
            functions.map { f -> f.valueParameters.map { it.name } },
        )
    }

    @Test
    fun onlyThingsWithABodyAreDeclarationsWithBody() {
        val parsed = file(
            """
            class Foo {
                init { }
                val p: Int get() = 1
                fun block() { }
                fun expr() = 1
                abstract fun none()
            }
            """.trimIndent(),
        )
        val withBody = parsed.all<KtDeclarationWithBody>()
        assertEquals(
            listOf("KtPropertyAccessor", "KtNamedFunction", "KtNamedFunction", "KtNamedFunction"),
            withBody.map { it::class.simpleName },
            "an init block is a KtAnonymousInitializer, not a declaration with a body",
        )

        val functions = parsed.all<KtNamedFunction>()
        assertEquals(listOf("block", "expr", "none"), functions.map { it.name })
        assertEquals(listOf(true, false, false), functions.map { it.hasBlockBody() })
        assertEquals(listOf(true, true, false), functions.map { it.hasBody() })

        // A primary constructor is a KtFunction with no body, which is the one place the two split.
        val primary = file("class Bar(val x: Int)").all<KtPrimaryConstructor>().single()
        assertFalse(primary.hasBody())
        assertNull(primary.bodyExpression)

        // An init block IS reached, under its own name.
        assertIs<KtClassInitializer>(parsed.all<KtAnonymousInitializer>().single())
    }

    @Test
    fun aLambdaBodyIsAlwaysABlock() {
        // `{ 1 }` has a single expression in the source and a block in the tree; upstream reports a block
        // body for every lambda, and a rule that asked `hasBlockBody()` to tell them apart would be wrong.
        val literal = file("val f = { 1 }").all<KtFunctionLiteral>().single()
        assertTrue(literal.hasBlockBody())
        assertTrue(literal.hasBody())
        assertNotNull(literal.bodyBlockExpression)
    }

    // --- ValueArgument -----------------------------------------------------------------------------------

    @Test
    fun aTrailingLambdaIsAValueArgumentToo() {
        // `lambda.parent as? ValueArgument` is how the inference code finds which parameter a lambda fills,
        // and a trailing lambda is not wrapped in a KtValueArgument: it IS the argument.
        val parsed = file("val x = run(a = 1, b) { 2 }")
        val arguments = parsed.all<ValueArgument>()
        assertEquals(
            listOf("KtValueArgument", "KtValueArgument", "KtLambdaArgument"),
            arguments.map { it::class.simpleName },
        )
        assertEquals(listOf("a", null, null), arguments.map { it.getArgumentName() })
        assertEquals(listOf(true, false, false), arguments.map { it.isNamed() })
        assertEquals(listOf("1", "b", "{ 2 }"), arguments.map { it.getArgumentExpression()?.text })
    }

    @Test
    fun anArgumentNameIsItsOwnNode() {
        val parsed = file("val x = f(a = 1)")
        val name = parsed.all<KtValueArgumentName>().single()
        assertEquals("a", name.text)
        assertIs<KtValueArgument>(name.parent)
    }

    // --- simple names, unary, labels ---------------------------------------------------------------------

    @Test
    fun bothReferenceKindsAreSimpleNames() {
        val parsed = file("val x = a + b")
        assertEquals(
            listOf("a", "+", "b"),
            parsed.all<KtSimpleNameExpression>().map { it.getReferencedName() },
            "an operator reference answers getReferencedName() with its sign",
        )
        // Backticks are part of the text and not part of the name.
        assertEquals("is", file("val y = `is`").all<KtSimpleNameExpression>().single().getReferencedName())
    }

    @Test
    fun prefixAndPostfixShareTheUnaryShape() {
        val parsed = file("fun f(i: Int) { -i; i++ }")
        val unary = parsed.all<KtUnaryExpression>()
        assertEquals(listOf("KtPrefixExpression", "KtPostfixExpression"), unary.map { it::class.simpleName })
        assertEquals(listOf("-", "++"), unary.map { it.operationReference?.text })
        assertEquals(listOf("i", "i"), unary.map { it.baseExpression?.text })
    }

    @Test
    fun everyLabelSiteReportsItsName() {
        val parsed = file(
            """
            fun f() {
                loop@ while (true) { break@loop }
                run { return@run }
            }
            """.trimIndent(),
        )
        val labeled = parsed.all<KtExpressionWithLabel>()
        assertEquals(
            listOf("KtLabeledExpression", "KtBreakExpression", "KtReturnExpression"),
            labeled.map { it::class.simpleName },
        )
        // A label is WRITTEN `loop@` and READ `@loop`, so the `@` sits on opposite ends of the same text.
        assertEquals(listOf("loop", "loop", "run"), labeled.map { it.getLabelName() })
        assertTrue(labeled.all { it.labelQualifier != null })
    }

    @Test
    fun thisAndSuperAreTheInstanceLabelSites() {
        val parsed = file("class Foo { fun f() { this@Foo; super.toString() } }")
        assertEquals(
            listOf("KtThisExpression", "KtSuperExpression"),
            parsed.all<KtInstanceExpressionWithLabel>().map { it::class.simpleName },
        )
        assertEquals(listOf("Foo", null), parsed.all<KtInstanceExpressionWithLabel>().map { it.getLabelName() })
    }

    @Test
    fun aLabelIsAReferenceOfItsOwn() {
        val parsed = file("fun f() { loop@ while (true) {} }")
        assertEquals("loop@", parsed.all<KtLabelReferenceExpression>().single().text)
    }

    // --- string templates --------------------------------------------------------------------------------

    @Test
    fun onlyInterpolatingEntriesCarryAnExpression() {
        // Constant folding asks `entry is KtStringTemplateEntryWithExpression` rather than asking every
        // entry for an expression, because a literal entry answering null is not the same as a `${'a'}`
        // entry whose expression happens to be missing.
        val parsed = file("""val s = "a${'$'}b${'$'}{c()}"""")
        val template = parsed.all<KtStringTemplateExpression>().single()
        assertEquals(
            listOf(false, true, true),
            template.entries.map { it is KtStringTemplateEntryWithExpression },
        )
        assertIs<KtLiteralStringTemplateEntry>(template.entries[0])
        assertEquals(
            listOf("b", "c()"),
            template.entries.filterIsInstance<KtStringTemplateEntryWithExpression>().map { it.expression?.text },
        )
        assertIs<KtBlockStringTemplateEntry>(template.entries[2])
    }

    // --- type parameters, annotations, modifiers ---------------------------------------------------------

    @Test
    fun theThreeTypeParameterOwnersAgree() {
        val parsed = file(
            """
            class Foo<A> {
                fun <B> f() {}
                val <C> List<C>.p: Int get() = 1
            }
            typealias Bar<D> = List<D>
            """.trimIndent(),
        )
        assertEquals(
            listOf(listOf("A"), listOf("B"), listOf("C"), listOf("D")),
            parsed.all<KtTypeParameterListOwner>().map { o -> o.typeParameters.map { it.name } },
        )
    }

    @Test
    fun annotationsAreReachableOnDeclarationsFilesAndTypes() {
        val parsed = file(
            """
            @file:JvmName("X")
            package p

            @Deprecated("m") class Foo {
                fun f(p: @A Int) {}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("JvmName"), parsed.annotationEntries.map { it.shortName })
        assertIs<KtFileAnnotationList>(parsed.fileAnnotationList)

        val cls = parsed.all<KtClass>().single()
        assertEquals(listOf("Deprecated"), cls.annotationEntries.map { it.shortName })
        assertIs<KtModifierListOwner>(cls)

        val onType = parsed.all<KtTypeReference>().single { it.annotationEntries.isNotEmpty() }
        assertEquals(listOf("A"), onType.annotationEntries.map { it.shortName })
    }

    @Test
    fun anExpressionIsNotAnnotatedJustBecauseItIsAnElement() {
        // KtAnnotated reaching every element would make `is KtAnnotated` always true, which is what the
        // diagnostics code dispatches on.
        val parsed = file("class Foo { fun f() { g() } }")
        val annotated = parsed.descendants().filter { it is KtAnnotated }.toList()
        assertTrue(parsed.all<KtDeclaration>().all { it in annotated }, "every declaration is annotatable")
        assertFalse(parsed.all<KtCallExpression>().single() in annotated, "a call is not")
    }

    // --- constructors and enum entries -------------------------------------------------------------------

    @Test
    fun aDelegationCallAndItsCalleeAreReachable() {
        val parsed = file(
            """
            class Foo(a: Int) {
                constructor() : this(1)
            }
            """.trimIndent(),
        )
        val secondary = parsed.all<KtSecondaryConstructor>().single()
        val call = assertNotNull(secondary.delegationCall)
        assertIs<KtConstructorDelegationCall>(call)
        assertEquals(listOf("1"), call.valueArguments.map { it.text })
    }

    @Test
    fun anAnnotationsCalleeIsItsOwnNode() {
        val parsed = file("@Deprecated(\"m\") class Foo")
        val callee = parsed.all<KtConstructorCalleeExpression>().single()
        assertEquals("Deprecated", callee.typeReference?.text)
    }

    // --- containers ---------------------------------------------------------------------------------------

    @Test
    fun controlStructureBranchesAreContainerNodes() {
        // `if (c) a else b` puts each of the three inside a wrapper node, which is why reaching a branch is
        // `child(THEN)?.firstChild` and never `children[1]`.
        val parsed = file("fun f(c: Boolean) { if (c) 1 else 2 }")
        val ifExpression = parsed.all<KtIfExpression>().single()
        val bodies = ifExpression.all<KtContainerNodeForControlStructureBody>()
        assertEquals(listOf("1", "2"), bodies.map { it.expression?.text })
        assertEquals("c", ifExpression.condition?.text)
    }

    @Test
    fun bothLoopsShareTheWhileShape() {
        val parsed = file("fun f() { while (a) b\n do c while (d) }")
        val loops = parsed.all<KtWhileExpressionBase>()
        assertEquals(listOf("KtWhileExpression", "KtDoWhileExpression"), loops.map { it::class.simpleName })
        assertEquals(listOf("a", "d"), loops.map { it.condition?.text })
        assertEquals(listOf("b", "c"), loops.map { it.body?.text })
    }

    // --- :: and containingClassOrObject -------------------------------------------------------------------

    @Test
    fun bothDoubleColonFormsShareAReceiver() {
        val parsed = file("val a = Foo::class\nval b = Foo::bar")
        val doubleColon = parsed.all<KtDoubleColonExpression>()
        assertEquals(
            listOf("KtClassLiteralExpression", "KtCallableReferenceExpression"),
            doubleColon.map { it::class.simpleName },
        )
        assertEquals(listOf("Foo", "Foo"), doubleColon.map { it.receiverExpression?.text })
    }

    @Test
    fun containingClassIsFoundThroughAllThreeShapes() {
        val parsed = file(
            """
            class Foo(val fromParameter: Int) {
                val fromBody = 1
            }
            enum class E { ENTRY }
            fun topLevel() {
                val local = 1
            }
            """.trimIndent(),
        )
        fun owner(name: String): String? =
            parsed.collectDescendantsOfType<KtDeclaration>()
                .first { (it as? KtProperty)?.name == name || (it as? KtEnumEntry)?.name == name || (it as? dev.ide.kotlin.syntax.psi.KtParameter)?.name == name }
                .containingClassOrObject?.name

        assertEquals("Foo", owner("fromBody"), "a member reaches the class through its body")
        assertEquals("Foo", owner("fromParameter"), "a constructor property reaches it through the parameter list")
        assertEquals("E", owner("ENTRY"), "an enum entry hangs off the class directly")
        assertNull(owner("local"), "a local is not a member of the class its function is in")
    }
}
