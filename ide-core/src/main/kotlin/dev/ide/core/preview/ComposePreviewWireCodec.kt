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
import dev.ide.lang.resolve.TypeRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Compact binary wire codec for a [LoweredComposePreview] — the "biggest single work item" of the Compose-preview
 * process isolation (see `docs/compose-preview-isolation.md`, Phase 1). The lowered preview (`entry`/`program`/
 * `classes`/`parameter`) is pure data (the `ResolvedTree` model — sealed `RNode`/`Binding`/`ResolvedCallable`,
 * `KotlinType`, no PSI), so it serializes to a blob that crosses the `IComposePreviewSession` AIDL boundary; the
 * `:preview` process decodes it back to the exact types the interpreter already consumes — no re-lowering (which
 * would need the full Kotlin symbol service + classpath in `:preview`, the RAM we are isolating away).
 *
 * The format is versioned (a magic header) and covers the whole model exhaustively (a missed field breaks the
 * round-trip — see `ComposePreviewWireCodecTest`). It is deliberately hand-written in the style of the index
 * `Segment` codec rather than a reflective serializer, so it has no dependency footprint and stays pure-JVM
 * (headless-testable on CI). `KotlinType.context` (a private, transient resolution callback) is intentionally
 * dropped and rebound locally after transport.
 */
object ComposePreviewWireCodec {

    private const val MAGIC = 0x43505731 // "CPW1"

    fun encode(preview: LoweredComposePreview): ByteArray {
        val bos = ByteArrayOutputStream()
        Writer(DataOutputStream(bos)).run {
            d.writeInt(MAGIC)
            preview(preview)
            d.flush()
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): LoweredComposePreview {
        val r = Reader(DataInputStream(ByteArrayInputStream(bytes)))
        require(r.d.readInt() == MAGIC) { "bad Compose preview wire magic" }
        return r.preview()
    }

    // ------------------------------------------------------------------ writer

    private class Writer(val d: DataOutputStream) {
        fun int(v: Int) = d.writeInt(v)
        fun bool(v: Boolean) = d.writeBoolean(v)
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); d.writeInt(b.size); d.write(b) }
        fun strN(s: String?) { bool(s != null); if (s != null) str(s) }
        fun <T> list(xs: List<T>, each: (T) -> Unit) { int(xs.size); xs.forEach(each) }
        fun <T> nullable(x: T?, write: (T) -> Unit) { bool(x != null); if (x != null) write(x) }
        fun <V> map(m: Map<String, V>, value: (V) -> Unit) { int(m.size); m.forEach { (k, v) -> str(k); value(v) } }

        fun span(s: SourceSpan) { int(s.start); int(s.end) }
        fun slot(s: SlotId) = int(s.value)

        fun preview(p: LoweredComposePreview) {
            function(p.entry)
            map(p.program) { function(it) }
            list(p.classes) { klass(it) }
            nullable(p.parameter) { param ->
                str(param.providerSimpleName); strN(param.providerFqn)
                nullable(param.providerClass) { klass(it) }; int(param.limit)
            }
        }

        fun type(t: KotlinType) {
            str(t.qualifiedName)
            list(t.typeArguments) { ta -> type((ta as? KotlinType) ?: KotlinType(ta.qualifiedName)) }
            bool(t.nullable); bool(t.isTypeParameter); bool(t.isExtensionFunctionType); bool(t.isComposable)
            str(t.projection)
        }

        fun binding(b: Binding): Unit = when (b) {
            is Binding.Local -> { d.writeByte(0); slot(b.slot); str(b.name); bool(b.mutable) }
            is Binding.Param -> { d.writeByte(1); slot(b.slot); str(b.name) }
            is Binding.DelegatedLocal -> { d.writeByte(2); slot(b.slot); str(b.name); bool(b.mutable); binding(b.valueProperty) }
            is Binding.DelegatedConvention -> { d.writeByte(3); slot(b.slot); str(b.name); bool(b.mutable); str(b.propertyName) }
            is Binding.Property -> { d.writeByte(4); str(b.name); strN(b.ownerFqn); bool(b.backingField); bool(b.isExtension) }
            is Binding.ObjectRef -> { d.writeByte(5); str(b.fqn); str(b.name) }
            is Binding.EnumEntry -> { d.writeByte(6); str(b.enumFqn); str(b.name) }
            is Binding.Receiver -> { d.writeByte(7); nullable(b.type) { type(it) }; int(b.depth); str(b.name) }
        }

        fun callable(c: ResolvedCallable): Unit = when (c) {
            is ResolvedCallable.Library -> {
                d.writeByte(0); str(c.displayName); strN(c.ownerFqn); str(c.methodName)
                list(c.paramTypes) { pt -> nullable(pt) { type(it) } }
                bool(c.isStatic); bool(c.isConstructor); bool(c.isInline); bool(c.isComposable)
                bool(c.descriptorPrecise); list(c.paramNames) { str(it) }; int(c.varargParamIndex)
                list(c.typeParameterNames) { str(it) }; bool(c.isSuspend)
            }
            is ResolvedCallable.Source -> {
                d.writeByte(1); str(c.displayName); str(c.declId); list(c.paramNames) { str(it) }
                bool(c.isConstructor); bool(c.isComposable); list(c.typeParameterNames) { str(it) }; bool(c.isSuspend)
            }
        }

        fun arg(a: RArg) { node(a.value); strN(a.name); bool(a.spread); bool(a.trailingLambda) }
        fun rparam(p: RParam) { slot(p.slot); str(p.name); nullable(p.type) { type(it) }; nullable(p.default) { node(it) }; bool(p.vararg) }
        fun typeArg(t: RTypeArg) { strN(t.fqn); list(t.loadCandidates) { str(it) }; strN(t.typeParamRef) }
        fun catch(c: RCatch) { slot(c.slot); str(c.name); strN(c.typeFqn); node(c.body) }
        fun diag(dg: LoweringDiagnostic) { str(dg.reason); span(dg.source) }

        fun constValue(v: Any?): Unit = when (v) {
            null -> d.writeByte(0)
            is String -> { d.writeByte(1); str(v) }
            is Int -> { d.writeByte(2); int(v) }
            is Long -> { d.writeByte(3); d.writeLong(v) }
            is Double -> { d.writeByte(4); d.writeDouble(v) }
            is Float -> { d.writeByte(5); d.writeFloat(v) }
            is Boolean -> { d.writeByte(6); bool(v) }
            is Char -> { d.writeByte(7); d.writeChar(v.code) }
            else -> error("unsupported Const.value type ${v.javaClass.name}")
        }

        fun node(n: RNode) {
            when (n) {
                is RNode.Const -> { d.writeByte(0); constValue(n.value); nullable(n.type) { type(it) } }
                is RNode.Name -> { d.writeByte(1); binding(n.binding) }
                is RNode.This -> { d.writeByte(2); binding(n.receiver) }
                is RNode.Call -> {
                    d.writeByte(3); callable(n.callee); d.writeByte(n.dispatch.ordinal); nullable(n.receiver) { node(it) }
                    list(n.args) { arg(it) }; int(n.callSiteKey.value); nullable(n.dispatchReceiver) { node(it) }
                    list(n.typeArguments) { typeArg(it) }
                }
                is RNode.PropertyGet -> { d.writeByte(4); nullable(n.receiver) { node(it) }; binding(n.binding) }
                is RNode.PropertySet -> { d.writeByte(5); nullable(n.receiver) { node(it) }; binding(n.binding); node(n.value) }
                is RNode.If -> { d.writeByte(6); node(n.condition); node(n.then); nullable(n.otherwise) { node(it) } }
                is RNode.Block -> { d.writeByte(7); list(n.statements) { node(it) }; bool(n.isExpression) }
                is RNode.Lambda -> { d.writeByte(8); list(n.params) { rparam(it) }; node(n.body); list(n.captures) { binding(it) }; bool(n.isLocalFunction) }
                is RNode.StringConcat -> { d.writeByte(9); list(n.parts) { node(it) } }
                is RNode.NotNull -> { d.writeByte(10); node(n.value) }
                is RNode.TypeCheck -> { d.writeByte(11); node(n.value); str(n.typeFqn); bool(n.negated); strN(n.reifiedParam) }
                is RNode.Cast -> { d.writeByte(12); node(n.value); str(n.typeFqn); bool(n.safe); bool(n.nullable); strN(n.reifiedParam) }
                is RNode.ClassLiteral -> { d.writeByte(13); nullable(n.receiver) { node(it) }; list(n.typeCandidates) { str(it) }; bool(n.asJava); strN(n.reifiedParam) }
                is RNode.Try -> { d.writeByte(14); node(n.body); list(n.catches) { catch(it) }; nullable(n.finallyBlock) { node(it) } }
                is RNode.LocalVar -> { d.writeByte(15); slot(n.slot); str(n.name); bool(n.mutable); nullable(n.initializer) { node(it) } }
                is RNode.Assign -> { d.writeByte(16); node(n.target); node(n.value) }
                is RNode.Return -> { d.writeByte(17); nullable(n.value) { node(it) } }
                is RNode.Throw -> { d.writeByte(18); node(n.value) }
                is RNode.Break -> { d.writeByte(19); strN(n.label) }
                is RNode.Continue -> { d.writeByte(20); strN(n.label) }
                is RNode.While -> { d.writeByte(21); node(n.condition); node(n.body); bool(n.doWhile); strN(n.label) }
                is RNode.ForEach -> {
                    d.writeByte(22); rparam(n.loopVar); node(n.iterable); nullable(n.iterator) { callable(it) }
                    nullable(n.hasNext) { callable(it) }; nullable(n.next) { callable(it) }; node(n.body); strN(n.label)
                }
                is RNode.Unsupported -> { d.writeByte(23); str(n.reason); str(n.text) }
            }
            span(n.source)
        }

        fun function(f: ResolvedFunction) {
            str(f.name); list(f.params) { rparam(it) }; node(f.body); list(f.diagnostics) { diag(it) }
            nullable(f.receiverSlot) { slot(it) }; bool(f.returnsUnit); bool(f.mutableBackingField)
        }

        fun classParam(p: RClassParam) { slot(p.slot); str(p.name); nullable(p.type) { type(it) }; bool(p.isProperty); bool(p.mutable); nullable(p.default) { node(it) } }
        fun enumEntry(e: REnumEntry) { str(e.name); int(e.ordinal); list(e.args) { arg(it) } }
        fun superCall(s: SuperCall) { str(s.fqn); list(s.args) { arg(it) } }
        fun secondaryCtor(s: SecondaryCtor) { list(s.params) { rparam(it) }; bool(s.delegatesToThis); list(s.delegationArgs) { arg(it) }; node(s.body); list(s.diagnostics) { diag(it) } }

        fun klass(c: ResolvedClass) {
            str(c.fqn); str(c.simpleName); d.writeByte(c.flavor.ordinal)
            bool(c.isData); bool(c.isSealed); bool(c.isAbstract)
            list(c.primaryParams) { classParam(it) }; list(c.initSteps) { node(it) }; map(c.methods) { function(it) }
            slot(c.receiverSlot); list(c.supertypes) { str(it) }; nullable(c.superCall) { superCall(it) }
            list(c.enumEntries) { enumEntry(it) }; list(c.diagnostics) { diag(it) }
            map(c.delegatedProperties) { str(it) }; map(c.conventionDelegatedProperties) { str(it) }
            list(c.secondaryCtors) { secondaryCtor(it) }; list(c.interfaceDelegates) { d -> str(d.interfaceFqn); str(d.fieldName) }
        }
    }

    // ------------------------------------------------------------------ reader

    private class Reader(val d: DataInputStream) {
        fun int() = d.readInt()
        fun bool() = d.readBoolean()
        fun str(): String { val b = ByteArray(d.readInt()); d.readFully(b); return String(b, Charsets.UTF_8) }
        fun strN(): String? = if (bool()) str() else null
        fun <T> list(each: () -> T): List<T> = (0 until int()).map { each() }
        fun <T> nullable(read: () -> T): T? = if (bool()) read() else null
        fun <V> map(value: () -> V): Map<String, V> {
            val n = int(); val m = LinkedHashMap<String, V>(n); repeat(n) { m[str()] = value() }; return m
        }

        fun span() = SourceSpan(int(), int())
        fun slot() = SlotId(int())

        fun preview(): LoweredComposePreview {
            val entry = function()
            val program = map { function() }
            val classes = list { klass() }
            val parameter = nullable {
                LoweredPreviewParameter(str(), strN(), nullable { klass() }, int())
            }
            return LoweredComposePreview(entry, program, classes, parameter)
        }

        fun type(): KotlinType {
            val qn = str()
            val args: List<TypeRef> = list { type() }
            val nullable = bool(); val isTp = bool(); val isExt = bool(); val isComp = bool()
            val proj = str()
            return KotlinType(
                qualifiedName = qn, typeArguments = args, nullable = nullable,
                isTypeParameter = isTp, isExtensionFunctionType = isExt, isComposable = isComp, projection = proj,
            )
        }

        fun binding(): Binding = when (val tag = d.readByte().toInt()) {
            0 -> Binding.Local(slot(), str(), bool())
            1 -> Binding.Param(slot(), str())
            2 -> Binding.DelegatedLocal(slot(), str(), bool(), binding() as Binding.Property)
            3 -> Binding.DelegatedConvention(slot(), str(), bool(), str())
            4 -> Binding.Property(str(), strN(), bool(), bool())
            5 -> Binding.ObjectRef(str(), str())
            6 -> Binding.EnumEntry(str(), str())
            7 -> Binding.Receiver(nullable { type() }, int(), str())
            else -> error("bad Binding tag $tag")
        }

        fun callable(): ResolvedCallable = when (val tag = d.readByte().toInt()) {
            0 -> ResolvedCallable.Library(
                displayName = str(), ownerFqn = strN(), methodName = str(),
                paramTypes = list { nullable { type() } },
                isStatic = bool(), isConstructor = bool(), isInline = bool(), isComposable = bool(),
                descriptorPrecise = bool(), paramNames = list { str() }, varargParamIndex = int(),
                typeParameterNames = list { str() }, isSuspend = bool(),
            )
            1 -> ResolvedCallable.Source(
                displayName = str(), declId = str(), paramNames = list { str() },
                isConstructor = bool(), isComposable = bool(), typeParameterNames = list { str() }, isSuspend = bool(),
            )
            else -> error("bad ResolvedCallable tag $tag")
        }

        fun arg() = RArg(node(), strN(), bool(), bool())
        fun rparam() = RParam(slot(), str(), nullable { type() }, nullable { node() }, bool())
        fun typeArg() = RTypeArg(strN(), list { str() }, strN())
        fun catch() = RCatch(slot(), str(), strN(), node())
        fun diag() = LoweringDiagnostic(str(), span())

        fun constValue(): Any? = when (val tag = d.readByte().toInt()) {
            0 -> null
            1 -> str()
            2 -> int()
            3 -> d.readLong()
            4 -> d.readDouble()
            5 -> d.readFloat()
            6 -> bool()
            7 -> d.readChar()
            else -> error("bad Const.value tag $tag")
        }

        fun node(): RNode {
            val tag = d.readByte().toInt()
            return when (tag) {
                0 -> RNode.Const(constValue(), nullable { type() }, span())
                1 -> RNode.Name(binding(), span())
                2 -> RNode.This(binding() as Binding.Receiver, span())
                3 -> RNode.Call(
                    callee = callable(), dispatch = DispatchKind.entries[d.readByte().toInt()],
                    receiver = nullable { node() }, args = list { arg() }, callSiteKey = CallSiteKey(int()),
                    dispatchReceiver = nullable { node() }, typeArguments = list { typeArg() }, source = span(),
                )
                4 -> RNode.PropertyGet(nullable { node() }, binding(), span())
                5 -> RNode.PropertySet(nullable { node() }, binding(), node(), span())
                6 -> RNode.If(node(), node(), nullable { node() }, span())
                7 -> RNode.Block(list { node() }, bool(), span())
                // Branches with fields AFTER `source` in the constructor read those fields (and everything else)
                // before `source = span()` so evaluation order matches the writer (which always writes span last).
                8 -> RNode.Lambda(list { rparam() }, node(), list { binding() }, isLocalFunction = bool(), source = span())
                9 -> RNode.StringConcat(list { node() }, span())
                10 -> RNode.NotNull(node(), span())
                11 -> RNode.TypeCheck(node(), str(), negated = bool(), reifiedParam = strN(), source = span())
                12 -> RNode.Cast(node(), str(), safe = bool(), nullable = bool(), reifiedParam = strN(), source = span())
                13 -> RNode.ClassLiteral(nullable { node() }, list { str() }, asJava = bool(), reifiedParam = strN(), source = span())
                14 -> RNode.Try(node(), list { catch() }, nullable { node() }, span())
                15 -> RNode.LocalVar(slot(), str(), bool(), nullable { node() }, span())
                16 -> RNode.Assign(node(), node(), span())
                17 -> RNode.Return(nullable { node() }, span())
                18 -> RNode.Throw(node(), span())
                19 -> RNode.Break(label = strN(), source = span())
                20 -> RNode.Continue(label = strN(), source = span())
                21 -> RNode.While(node(), node(), doWhile = bool(), label = strN(), source = span())
                22 -> RNode.ForEach(
                    loopVar = rparam(), iterable = node(), iterator = nullable { callable() },
                    hasNext = nullable { callable() }, next = nullable { callable() }, body = node(),
                    label = strN(), source = span(),
                )
                23 -> RNode.Unsupported(str(), str(), span())
                else -> error("bad RNode tag $tag")
            }
        }

        fun function(): ResolvedFunction = ResolvedFunction(
            name = str(), params = list { rparam() }, body = node(), diagnostics = list { diag() },
            receiverSlot = nullable { slot() }, returnsUnit = bool(), mutableBackingField = bool(),
        )

        fun classParam() = RClassParam(slot(), str(), nullable { type() }, bool(), bool(), nullable { node() })
        fun enumEntry() = REnumEntry(str(), int(), list { arg() })
        fun superCall() = SuperCall(str(), list { arg() })
        fun secondaryCtor() = SecondaryCtor(list { rparam() }, bool(), list { arg() }, node(), list { diag() })

        fun klass(): ResolvedClass = ResolvedClass(
            fqn = str(), simpleName = str(), flavor = ClassFlavor.entries[d.readByte().toInt()],
            isData = bool(), isSealed = bool(), isAbstract = bool(),
            primaryParams = list { classParam() }, initSteps = list { node() }, methods = map { function() },
            receiverSlot = slot(), supertypes = list { str() }, superCall = nullable { superCall() },
            enumEntries = list { enumEntry() }, diagnostics = list { diag() },
            delegatedProperties = map { str() }, conventionDelegatedProperties = map { str() },
            secondaryCtors = list { secondaryCtor() }, interfaceDelegates = list { InterfaceDelegate(str(), str()) },
        )
    }
}
