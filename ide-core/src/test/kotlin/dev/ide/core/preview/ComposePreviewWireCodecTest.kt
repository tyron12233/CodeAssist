package dev.ide.core.preview

import dev.ide.core.LoweredComposePreview
import dev.ide.core.LoweredPreviewParameter
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.InterfaceDelegate
import dev.ide.lang.kotlin.interp.LoweringDiagnostic
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RCatch
import dev.ide.lang.kotlin.interp.RClassParam
import dev.ide.lang.kotlin.interp.REnumEntry
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.RTypeArg
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SecondaryCtor
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.interp.SuperCall
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip proof for the Compose-preview wire codec (`docs/compose-preview-isolation.md`, Phase 1): the lowered
 * program must survive encode → bytes → decode byte-for-byte, so `:preview` reconstructs exactly what the IDE
 * lowered. Headless (pure JVM, CI). The comprehensive case exercises all 24 `RNode` variants, all 8 `Binding`
 * variants, both `ResolvedCallable` shapes, a full `ResolvedClass`, the parameter provider, and every `Const`
 * value type — so a missed field fails the structural `assertEquals`. `KotlinType` (identity-equals, not a data
 * class) is covered separately by field comparison.
 */
class ComposePreviewWireCodecTest {

    private val span = SourceSpan(3, 7)
    private fun c(v: Any?) = RNode.Const(v, null, span)
    private fun lib(name: String, disp: Boolean = false) = ResolvedCallable.Library(
        displayName = name, ownerFqn = "com.Owner", methodName = name, paramTypes = listOf(null, null),
        isStatic = true, isConstructor = false, isInline = false, isComposable = true,
        descriptorPrecise = true, paramNames = listOf("a", "b"), varargParamIndex = 1,
        typeParameterNames = listOf("T"), isSuspend = disp,
    )
    private val src = ResolvedCallable.Source("Helper", "com.Helper/0", listOf("x"), isConstructor = false, isComposable = true)

    private fun smallFn(name: String) = ResolvedFunction(name, emptyList(), c("body"), emptyList())

    /** Every `RNode` variant at least once, threaded through a Block; all `type`/KotlinType fields left null so the
     *  data classes compare structurally (KotlinType is identity-equals — see the dedicated test). */
    private fun allNodes(): List<RNode> = listOf(
        c("s"), c(42), c(9_000_000_000L), c(3.14), c(2.5f), c(true), c('x'), c(null),
        RNode.Name(Binding.Local(SlotId(1), "l", true), span),
        RNode.Name(Binding.Param(SlotId(2), "p"), span),
        RNode.Name(Binding.Property("prop", "com.Foo", backingField = false, isExtension = true), span),
        RNode.Name(Binding.ObjectRef("com.Obj", "Obj"), span),
        RNode.Name(Binding.EnumEntry("com.E", "A"), span),
        RNode.Name(Binding.Receiver(null, 1, "this"), span),
        RNode.Name(Binding.DelegatedLocal(SlotId(3), "dl", false, Binding.Property("v", "com.S", false, false)), span),
        RNode.Name(Binding.DelegatedConvention(SlotId(4), "dc", true, "getValue"), span),
        RNode.This(Binding.Receiver(null, 0, "this"), span),
        RNode.Call(
            lib("M"), DispatchKind.MEMBER, receiver = c("r"),
            args = listOf(RArg(c(1), name = "a", spread = true, trailingLambda = false)),
            callSiteKey = CallSiteKey(11), dispatchReceiver = c("dr"),
            typeArguments = listOf(RTypeArg("com.T", listOf("c1", "c2"), "R")), source = span,
        ),
        RNode.Call(src, DispatchKind.TOP_LEVEL, receiver = null, args = emptyList(), callSiteKey = CallSiteKey(12), source = span),
        RNode.PropertyGet(c("recv"), Binding.Property("x", "com.Y", backingField = true, isExtension = false), span),
        RNode.PropertySet(null, Binding.Property("y", null, false, false), c(5), span),
        RNode.If(c(true), c(1), c(0), span),
        RNode.If(c(false), c(1), null, span),
        RNode.Block(listOf(c(1)), isExpression = true, span),
        RNode.Lambda(listOf(RParam(SlotId(5), "lp", null, c("d"), true)), c("lbody"), listOf(Binding.Param(SlotId(2), "p")), span, isLocalFunction = true),
        RNode.StringConcat(listOf(c("a"), c("b")), span),
        RNode.NotNull(c("nn"), span),
        RNode.TypeCheck(c("v"), "com.Z", negated = true, span, reifiedParam = "R"),
        RNode.TypeCheck(c("v"), "com.Z", negated = false, span),
        RNode.Cast(c("v"), "com.C", safe = true, nullable = false, span, reifiedParam = null),
        RNode.ClassLiteral(c("cl"), listOf("c"), asJava = true, span, reifiedParam = "R"),
        RNode.ClassLiteral(null, listOf("c"), asJava = false, span),
        RNode.Try(c("tbody"), listOf(RCatch(SlotId(6), "e", "com.Ex", c("h"))), c("fin"), span),
        RNode.Try(c("b"), emptyList(), null, span),
        RNode.LocalVar(SlotId(7), "lv", true, c("init"), span),
        RNode.LocalVar(SlotId(8), "lv2", false, null, span),
        RNode.Assign(RNode.Name(Binding.Local(SlotId(2), "l", true), span), c(9), span),
        RNode.Return(c("ret"), span),
        RNode.Return(null, span),
        RNode.Throw(c("t"), span),
        RNode.Break(span, label = "loop"),
        RNode.Break(span),
        RNode.Continue(span, label = "loop"),
        RNode.Continue(span),
        RNode.While(c(true), c("wbody"), doWhile = false, span, label = "wl"),
        RNode.While(c(false), c("b"), doWhile = true, span),
        RNode.ForEach(RParam(SlotId(9), "fv", null, null, false), c("iter"), src, src, src, c("febody"), span, label = "fl"),
        RNode.ForEach(RParam(SlotId(10), "fv2", null, null, false), c("it2"), null, null, null, c("b2"), span),
        RNode.Unsupported("reason", "text", span),
    )

    private fun fullClass() = ResolvedClass(
        fqn = "com.Model", simpleName = "Model", flavor = ClassFlavor.ENUM,
        isData = true, isSealed = false, isAbstract = true,
        primaryParams = listOf(RClassParam(SlotId(20), "cp", null, isProperty = true, mutable = false, default = c("cd"))),
        initSteps = listOf(c("init")),
        methods = mapOf("m/0" to smallFn("m")),
        receiverSlot = SlotId(0),
        supertypes = listOf("com.Super"),
        superCall = SuperCall("com.Super", listOf(RArg(c(1)))),
        enumEntries = listOf(REnumEntry("A", 0, listOf(RArg(c("ea"))))),
        diagnostics = listOf(LoweringDiagnostic("d", span)),
        delegatedProperties = mapOf("dp" to "getDp"),
        conventionDelegatedProperties = mapOf("cdp" to "getCdp"),
        secondaryCtors = listOf(SecondaryCtor(listOf(RParam(SlotId(21), "sp", null, null, false)), delegatesToThis = true, listOf(RArg(c(2))), c("sbody"), emptyList())),
        interfaceDelegates = listOf(InterfaceDelegate("com.I", "field")),
    )

    private fun preview() = LoweredComposePreview(
        entry = ResolvedFunction(
            name = "Preview",
            params = listOf(RParam(SlotId(1), "p", null, c("pd"), false)),
            body = RNode.Block(allNodes(), isExpression = false, span),
            diagnostics = listOf(LoweringDiagnostic("fd", span)),
            receiverSlot = SlotId(30), returnsUnit = true, mutableBackingField = true,
            // Regression: the pre-extraction codec MISSED this field, so `:preview` decoded every top-level
            // `val` as re-evaluated-per-read and custom-theme CompositionLocal identity broke remotely.
            singletonBackingField = true,
        ),
        program = mapOf("Preview/1" to smallFn("Preview"), "Helper/0" to smallFn("Helper")),
        classes = listOf(fullClass()),
        parameter = LoweredPreviewParameter("Provider", "com.Provider", providerClass = fullClass(), limit = 5),
    )

    @Test
    fun roundTripsAComprehensivePreview() {
        val original = preview()
        val decoded = ComposePreviewWireCodec.decode(ComposePreviewWireCodec.encode(original))
        assertEquals(original, decoded, "the lowered preview must survive encode → bytes → decode byte-for-byte")
    }

    @Test
    fun roundTripsKotlinType() {
        // KotlinType is identity-equals (not a data class); carry one through RParam.type and compare fields.
        val kt = KotlinType(
            qualifiedName = "com.Foo",
            typeArguments = listOf(KotlinType(qualifiedName = "com.Bar", nullable = true)),
            nullable = false, isTypeParameter = false, isExtensionFunctionType = true, isComposable = true,
            projection = "out",
        )
        val original = LoweredComposePreview(
            entry = ResolvedFunction("P", listOf(RParam(SlotId(1), "x", kt)), c("b"), emptyList()),
            program = emptyMap(),
        )
        val decoded = ComposePreviewWireCodec.decode(ComposePreviewWireCodec.encode(original))
        val got = decoded.entry.params.single().type!!
        assertEquals("com.Foo", got.qualifiedName)
        assertEquals(false, got.nullable)
        assertTrue(got.isExtensionFunctionType); assertTrue(got.isComposable); assertTrue(!got.isTypeParameter)
        assertEquals("out", got.projection)
        val arg = got.typeArguments.single() as KotlinType
        assertEquals("com.Bar", arg.qualifiedName)
        assertTrue(arg.nullable)
    }
}
