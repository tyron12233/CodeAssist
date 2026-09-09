package dev.ide.android.support.aidl

/** One generated `.java` file: its fully-qualified type name, its path relative to the output root, and its source. */
data class AidlJavaSource(val fqName: String, val relativePath: String, val source: String)

/**
 * The AIDL → Java backend: turns one parsed declaration into the source the reference `aidl` tool would.
 *
 * An `interface` becomes the familiar four-part type: the interface itself, a `Default` no-op
 * implementation, an abstract `Stub` (a `Binder` that decodes `onTransact`), and a private `Proxy` (an
 * `IBinder` wrapper that encodes calls). The generated API matches what Android developers already write
 * against: `IFoo.Stub.asInterface(binder)`, `extends IFoo.Stub`, `IFoo.Stub.setDefaultImpl(…)`.
 *
 * A structured `parcelable` becomes a class with AIDL's length-prefixed framing, so adding a field at the end
 * stays readable by a peer built against the older definition. An `enum` becomes an annotation type holding
 * `static final` constants of its backing integral type. The Java backend has no Java enum, which is why a
 * value of an AIDL enum type is just a `byte`/`int` in every signature.
 *
 * Semantic problems (a `oneway` method with a return value, an `out` parameter that cannot carry one back, an
 * unresolved type) are reported through the diagnostic sink and generation carries on, so one build surfaces
 * every problem in the file rather than only the first. No source is produced for a declaration that reported
 * an error; half-generated Java would bury the real message under a cascade of javac ones.
 */
object AidlJavaGenerator {

    private const val DATA = "_aidl_data"
    private const val REPLY = "_aidl_reply"
    private const val CODE = "_aidl_code"
    private const val FLAGS = "_aidl_flags"
    private const val STATUS = "_aidl_status"
    private const val RESULT = "_aidl_ret"

    /**
     * Generate the Java for [decl] of [file], or null when the declaration produces no code: a
     * `parcelable Foo;` forward declaration, an `interface IFoo;` name assertion, or a declaration that
     * failed validation.
     */
    fun generate(
        file: AidlFile,
        decl: AidlDecl,
        table: AidlTypeTable,
        report: (AidlDiagnostic) -> Unit,
    ): AidlJavaSource? {
        // A qualified name only occurs in a preprocessed file (framework.aidl), which is a type table, not input.
        if ('.' in decl.name) return null
        val ctx = Context(file, table, report)
        val source = when (decl) {
            is AidlParcelableDecl -> return null
            is AidlInterface -> if (decl.forwardDeclaration) return null else generateInterface(decl, ctx)
            is AidlStructuredParcelable -> generateParcelable(decl, ctx)
            is AidlEnum -> generateEnum(decl, ctx)
            is AidlUnion -> {
                ctx.error(decl.pos, "AIDL unions are not supported yet; use a parcelable with a discriminator field.")
                return null
            }
        }
        if (ctx.errors > 0) return null
        val fq = qualified(file, decl.name)
        return AidlJavaSource(fq, fq.replace('.', '/') + ".java", source)
    }

    /**
     * Per-file generation state. Every diagnostic, the resolver's included, flows through [report] so the
     * error count reflects the whole pass, which is what decides whether any source is emitted at all.
     */
    private class Context(val file: AidlFile, table: AidlTypeTable, sink: (AidlDiagnostic) -> Unit) {
        var errors = 0
            private set

        private val report: (AidlDiagnostic) -> Unit = { d ->
            if (d.severity == AidlSeverity.ERROR) errors++
            sink(d)
        }

        private val resolver = AidlTypeResolver(table, file.packageName, file.imports, file.path, report)

        fun resolve(ref: AidlTypeRef, asReturn: Boolean = false): AidlType = resolver.resolve(ref, asReturn)

        fun error(pos: AidlPos, message: String) = report(AidlDiagnostic(AidlSeverity.ERROR, message, file.path, pos))
    }

    // ---------------------------------------------------------------- interface

    /** A method with its types resolved, its direction defaults applied and its transaction id fixed. */
    private class Method(
        val src: AidlMethod,
        val returnType: AidlType,
        val params: List<Param>,
        val transactionId: Int,
        val oneway: Boolean,
    ) {
        val name: String get() = src.name
        val returnsValue: Boolean get() = returnType != AidlType.Void

        /** `int add(int a, int b)`: the signature shared by the interface, `Default`, `Stub` and `Proxy`. */
        fun signature(): String =
            "${returnType.java} $name(${params.joinToString(", ") { "${it.type.java} ${it.name}" }})"

        /** The call arguments, in order: the caller's own names in a proxy, `_aidl_argN` in the stub. */
        fun arguments(name: (Int, Param) -> String): String =
            params.withIndex().joinToString(", ") { (i, p) -> name(i, p) }
    }

    private class Param(val src: AidlParam, val type: AidlType, val direction: AidlDirection) {
        val name: String get() = src.name

        /** True when the callee may fill this in: the value has to travel back in the reply. */
        val isOut: Boolean get() = direction != AidlDirection.IN

        /** True when the caller's value travels out: `in` and `inout`, but not `out`. */
        val readsFromCaller: Boolean get() = direction != AidlDirection.OUT
    }

    private fun generateInterface(decl: AidlInterface, ctx: Context): String {
        val self = qualified(ctx.file, decl.name)
        val methods = resolveMethods(decl, ctx)
        val w = JavaWriter()
        header(w, ctx.file)
        w.doc(decl.doc)
        w.block("public interface ${decl.name} extends android.os.IInterface") {
            generateDefaultImpl(w, self, methods)
            w.line()
            generateStub(w, self, methods)
            w.line()
            for (c in decl.constants) {
                w.doc(c.doc)
                w.line("public static final ${ctx.resolve(c.type).java} ${c.name} = ${c.value};")
            }
            for (m in methods) {
                w.doc(m.src.doc)
                w.line("public ${m.signature()} throws android.os.RemoteException;")
            }
        }
        return w.toString()
    }

    /**
     * Resolve every method: apply direction defaults, assign transaction ids, and reject the combinations the
     * Java backend cannot express (`oneway` with a result, `out` on a type that cannot carry one back).
     */
    private fun resolveMethods(decl: AidlInterface, ctx: Context): List<Method> {
        val methods = decl.methods.mapIndexed { index, m ->
            val returnType = ctx.resolve(m.returnType, asReturn = true)
            val params = m.params.map { p -> resolveParam(p, ctx) }
            if (m.oneway) {
                if (returnType != AidlType.Void) {
                    ctx.error(m.pos, "oneway method '${m.name}' must return void; a oneway call has no reply.")
                }
                for (p in params.filter { it.isOut }) {
                    ctx.error(p.src.pos, "oneway method '${m.name}' cannot have an out/inout parameter '${p.name}'.")
                }
            }
            // Without an explicit `= N`, a method's transaction id is its position, so reordering the file
            // reorders the wire protocol: the same rule, and the same hazard, as the reference compiler.
            Method(m, returnType, params, m.transactionId ?: index, m.oneway)
        }
        for (clash in methods.groupBy { it.transactionId }.values.filter { it.size > 1 }) {
            ctx.error(
                clash[1].src.pos,
                "transaction id ${clash[0].transactionId} is used by more than one method: ${clash.joinToString { it.name }}",
            )
        }
        return methods
    }

    /**
     * A parameter's direction. AIDL implies `in` only where nothing could travel back: a primitive, a String,
     * a binder. For anything that *could* be filled in by the callee the direction has to be written down, so
     * that a forgotten `out` is a build error rather than a silently discarded result at runtime.
     */
    private fun resolveParam(p: AidlParam, ctx: Context): Param {
        val type = ctx.resolve(p.type)
        if (type == AidlType.FileDescriptor) {
            ctx.error(
                p.pos,
                "'FileDescriptor' needs platform-private Parcel APIs and cannot be used by an app; " +
                    "use 'ParcelFileDescriptor' instead.",
            )
        }
        val canBeOut = AidlMarshalling.supportsOut(type)
        val direction = p.direction ?: run {
            if (canBeOut) {
                ctx.error(
                    p.pos,
                    "'${p.type}' can carry a value back, so parameter '${p.name}' must say 'in', 'out' or 'inout'.",
                )
            }
            AidlDirection.IN
        }
        if (direction != AidlDirection.IN && !canBeOut) {
            ctx.error(p.pos, "'${p.type}' cannot be an out/inout parameter; only arrays, lists and parcelables can.")
        }
        return Param(p, type, direction)
    }

    private fun generateDefaultImpl(w: JavaWriter, self: String, methods: List<Method>) {
        w.line("/** Default implementation for this interface: every call is a no-op. */")
        w.block("public static class Default implements $self") {
            for (m in methods) {
                w.block("@Override public ${m.signature()} throws android.os.RemoteException") {
                    if (m.returnsValue) w.line("return ${AidlMarshalling.defaultValue(m.returnType)};")
                }
            }
            w.block("@Override public android.os.IBinder asBinder()") { w.line("return null;") }
        }
    }

    private fun generateStub(w: JavaWriter, self: String, methods: List<Method>) {
        w.line("/** Local-side IPC implementation stub class. */")
        w.block("public static abstract class Stub extends android.os.Binder implements $self") {
            w.line("public static final java.lang.String DESCRIPTOR = \"$self\";")
            w.line()
            w.line("/** Construct the stub and attach it to the interface. */")
            w.block("public Stub()") { w.line("this.attachInterface(this, DESCRIPTOR);") }
            w.line()
            w.line("/** Cast an IBinder object into an $self interface, generating a proxy if needed. */")
            w.block("public static $self asInterface(android.os.IBinder obj)") {
                w.block("if ((obj==null))") { w.line("return null;") }
                w.line("android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);")
                w.block("if (((iin!=null)&&(iin instanceof $self)))") { w.line("return (($self)iin);") }
                w.line("return new $self.Stub.Proxy(obj);")
            }
            w.line()
            w.block("@Override public android.os.IBinder asBinder()") { w.line("return this;") }
            w.line()
            generateOnTransact(w, methods)
            w.line()
            generateProxy(w, self, methods)
            w.line()
            generateDefaultImplAccessors(w, self)
            w.line()
            for (m in methods) {
                w.line("static final int TRANSACTION_${m.name} = (android.os.IBinder.FIRST_CALL_TRANSACTION + ${m.transactionId});")
            }
        }
    }

    /**
     * `setDefaultImpl`/`getDefaultImpl`: the fallback an app installs so calls still do something when the
     * remote side is absent. A proxy consults it when `transact` reports the transaction was not handled.
     */
    private fun generateDefaultImplAccessors(w: JavaWriter, self: String) {
        w.block("public static boolean setDefaultImpl($self impl)") {
            w.line("// Only one user of this interface can use this function at a time; a second call is a")
            w.line("// heuristic for two different users in the same process fighting over it.")
            w.block("if (Stub.Proxy.sDefaultImpl != null)") {
                w.line("throw new java.lang.IllegalStateException(\"setDefaultImpl() called twice\");")
            }
            w.block("if (impl != null)") {
                w.line("Stub.Proxy.sDefaultImpl = impl;")
                w.line("return true;")
            }
            w.line("return false;")
        }
        w.block("public static $self getDefaultImpl()") { w.line("return Stub.Proxy.sDefaultImpl;") }
    }

    /**
     * The stub's decoder. Each transaction gets a `case` that returns directly, so the switch needs no
     * `default` and the trailing `super.onTransact` covers both an unknown code and an interface with no
     * methods at all, where a `default:`-only switch would make the statement after it unreachable and the
     * generated file would not compile.
     */
    private fun generateOnTransact(w: JavaWriter, methods: List<Method>) {
        val signature = "@Override public boolean onTransact(int $CODE, android.os.Parcel $DATA, " +
            "android.os.Parcel $REPLY, int $FLAGS) throws android.os.RemoteException"
        w.block(signature) {
            w.line("java.lang.String descriptor = DESCRIPTOR;")
            w.block("if ($CODE >= android.os.IBinder.FIRST_CALL_TRANSACTION && $CODE <= android.os.IBinder.LAST_CALL_TRANSACTION)") {
                w.line("$DATA.enforceInterface(descriptor);")
            }
            w.block("if ($CODE == android.os.IBinder.INTERFACE_TRANSACTION)") {
                w.line("$REPLY.writeString(descriptor);")
                w.line("return true;")
            }
            w.block("switch ($CODE)") {
                for (m in methods) {
                    w.line("case TRANSACTION_${m.name}:")
                    w.block("") { generateTransactCase(w, m) }
                }
            }
            w.line("return super.onTransact($CODE, $DATA, $REPLY, $FLAGS);")
        }
    }

    private fun generateTransactCase(w: JavaWriter, m: Method) {
        for ((i, p) in m.params.withIndex()) {
            val local = "_aidl_arg$i"
            w.line("${p.type.java} $local;")
            // An `out` parameter arrived as nothing but (for an array) its length: build the object here.
            if (p.readsFromCaller) AidlMarshalling.read(w, DATA, local, p.type)
            else AidlMarshalling.allocateOut(w, DATA, local, p.type)
        }
        val call = "this.${m.name}(${m.arguments { i, _ -> "_aidl_arg$i" }})"
        if (m.returnsValue) w.line("${m.returnType.java} $RESULT = $call;") else w.line("$call;")
        if (!m.oneway) {
            w.line("$REPLY.writeNoException();")
            if (m.returnsValue) AidlMarshalling.write(w, REPLY, RESULT, m.returnType, AidlMarshalling.RETURN_FLAGS)
            // `out`/`inout` results ride home behind the return value, in declaration order; the proxy reads
            // them back in exactly this sequence.
            for ((i, p) in m.params.withIndex()) {
                if (p.isOut) AidlMarshalling.write(w, REPLY, "_aidl_arg$i", p.type, AidlMarshalling.RETURN_FLAGS)
            }
        }
        w.line("return true;")
    }

    private fun generateProxy(w: JavaWriter, self: String, methods: List<Method>) {
        w.block("private static class Proxy implements $self") {
            w.line("private android.os.IBinder mRemote;")
            w.line("public static $self sDefaultImpl;")
            w.line()
            w.block("Proxy(android.os.IBinder remote)") { w.line("mRemote = remote;") }
            w.line()
            w.block("@Override public android.os.IBinder asBinder()") { w.line("return mRemote;") }
            w.line()
            w.block("public java.lang.String getInterfaceDescriptor()") { w.line("return DESCRIPTOR;") }
            for (m in methods) {
                w.line()
                generateProxyMethod(w, m)
            }
        }
    }

    private fun generateProxyMethod(w: JavaWriter, m: Method) {
        w.block("@Override public ${m.signature()} throws android.os.RemoteException") {
            w.line("android.os.Parcel $DATA = android.os.Parcel.obtain();")
            if (!m.oneway) w.line("android.os.Parcel $REPLY = android.os.Parcel.obtain();")
            if (m.returnsValue) w.line("${m.returnType.java} $RESULT;")
            w.block("try") {
                w.line("$DATA.writeInterfaceToken(DESCRIPTOR);")
                for (p in m.params) {
                    // `out`: the value itself stays here; only an array's length travels, so the stub can
                    // allocate one the same size for the implementation to fill.
                    if (p.readsFromCaller) AidlMarshalling.write(w, DATA, p.name, p.type, AidlMarshalling.ARG_FLAGS)
                    else AidlMarshalling.writeOutHeader(w, DATA, p.name, p.type)
                }
                val replyArg = if (m.oneway) "null" else REPLY
                val flags = if (m.oneway) "android.os.IBinder.FLAG_ONEWAY" else "0"
                w.line("boolean $STATUS = mRemote.transact(Stub.TRANSACTION_${m.name}, $DATA, $replyArg, $flags);")
                w.block("if (!$STATUS && getDefaultImpl() != null)") {
                    val forward = "getDefaultImpl().${m.name}(${m.arguments { _, p -> p.name }})"
                    if (m.returnsValue) {
                        w.line("return $forward;")
                    } else {
                        w.line("$forward;")
                        w.line("return;")
                    }
                }
                if (!m.oneway) {
                    w.line("$REPLY.readException();")
                    if (m.returnsValue) AidlMarshalling.read(w, REPLY, RESULT, m.returnType)
                    for (p in m.params) if (p.isOut) AidlMarshalling.readBack(w, REPLY, p.name, p.type)
                }
            }
            w.block("finally") {
                if (!m.oneway) w.line("$REPLY.recycle();")
                w.line("$DATA.recycle();")
            }
            if (m.returnsValue) w.line("return $RESULT;")
        }
    }

    // ---------------------------------------------------------------- structured parcelable

    private const val P_PARCEL = "_aidl_parcel"
    private const val P_START = "_aidl_start_pos"
    private const val P_SIZE = "_aidl_parcelable_size"

    /**
     * A structured parcelable, with AIDL's length-prefixed framing: the payload is preceded by its own byte
     * size, and reading stops as soon as that size is exhausted. That is what lets a peer built against an
     * older version of the same `.aidl` file read a parcel which has since gained fields at the end.
     */
    private fun generateParcelable(decl: AidlStructuredParcelable, ctx: Context): String {
        val fields = decl.fields.map { it to ctx.resolve(it.type) }
        val w = JavaWriter()
        header(w, ctx.file)
        w.doc(decl.doc)
        w.block("public class ${decl.name} implements android.os.Parcelable") {
            for (c in decl.constants) {
                w.doc(c.doc)
                w.line("public static final ${ctx.resolve(c.type).java} ${c.name} = ${c.value};")
            }
            for ((field, type) in fields) {
                w.doc(field.doc)
                w.line("public ${type.java} ${field.name}${field.defaultValue?.let { " = $it" } ?: ""};")
            }
            w.line()
            val creator = "android.os.Parcelable.Creator<${decl.name}>"
            w.line("public static final $creator CREATOR = new $creator() {")
            w.indent {
                w.block("@Override public ${decl.name} createFromParcel(android.os.Parcel _aidl_source)") {
                    w.line("${decl.name} _aidl_out = new ${decl.name}();")
                    w.line("_aidl_out.readFromParcel(_aidl_source);")
                    w.line("return _aidl_out;")
                }
                w.block("@Override public ${decl.name}[] newArray(int _aidl_size)") {
                    w.line("return new ${decl.name}[_aidl_size];")
                }
            }
            w.line("};")
            w.line()
            generateParcelableWrite(w, fields)
            w.line()
            generateParcelableRead(w, fields)
            w.line()
            generateDescribeContents(w, fields)
        }
        return w.toString()
    }

    private fun generateParcelableWrite(w: JavaWriter, fields: List<Pair<AidlField, AidlType>>) {
        w.block("@Override public final void writeToParcel(android.os.Parcel $P_PARCEL, int _aidl_flag)") {
            w.line("int $P_START = $P_PARCEL.dataPosition();")
            w.line("$P_PARCEL.writeInt(0);")
            for ((field, type) in fields) AidlMarshalling.write(w, P_PARCEL, field.name, type, "_aidl_flag")
            w.line("int _aidl_end_pos = $P_PARCEL.dataPosition();")
            w.line("$P_PARCEL.setDataPosition($P_START);")
            w.line("$P_PARCEL.writeInt(_aidl_end_pos - $P_START);")
            w.line("$P_PARCEL.setDataPosition(_aidl_end_pos);")
        }
    }

    private fun generateParcelableRead(w: JavaWriter, fields: List<Pair<AidlField, AidlType>>) {
        w.block("public final void readFromParcel(android.os.Parcel $P_PARCEL)") {
            w.line("int $P_START = $P_PARCEL.dataPosition();")
            w.line("int $P_SIZE = $P_PARCEL.readInt();")
            w.block("try") {
                w.block("if ($P_SIZE < 4)") {
                    w.line("throw new android.os.BadParcelableException(\"Parcelable too small\");")
                }
                for ((field, type) in fields) {
                    // Stop as soon as the writer's declared size runs out: the remaining fields were added
                    // after the peer's copy of this parcelable was generated, and keep their defaults.
                    w.block("if ($P_PARCEL.dataPosition() - $P_START >= $P_SIZE)") { w.line("return;") }
                    AidlMarshalling.read(w, P_PARCEL, field.name, type)
                }
            }
            w.block("finally") {
                w.block("if ($P_START > (java.lang.Integer.MAX_VALUE - $P_SIZE))") {
                    w.line("throw new android.os.BadParcelableException(\"Overflow in the size of parcelable\");")
                }
                w.line("$P_PARCEL.setDataPosition($P_START + $P_SIZE);")
            }
        }
    }

    /**
     * `describeContents` has to report special objects anywhere in the payload (a file descriptor inside a
     * nested parcelable), so it walks the reference-typed fields. When no field can hold one it collapses to
     * the constant 0 and the walker is not emitted at all.
     */
    private fun generateDescribeContents(w: JavaWriter, fields: List<Pair<AidlField, AidlType>>) {
        val walked = fields.filter { (_, type) -> mayHoldDescriptor(type) }
        if (walked.isEmpty()) {
            w.block("@Override public int describeContents()") { w.line("return 0;") }
            return
        }
        w.block("@Override public int describeContents()") {
            w.line("int _mask = 0;")
            for ((field, _) in walked) w.line("_mask |= describeContents(${field.name});")
            w.line("return _mask;")
        }
        w.block("private int describeContents(java.lang.Object _v)") {
            w.block("if (_v == null)") { w.line("return 0;") }
            w.block("if (_v instanceof java.lang.Object[])") {
                w.line("int _mask = 0;")
                w.block("for (java.lang.Object o : (java.lang.Object[]) _v)") { w.line("_mask |= describeContents(o);") }
                w.line("return _mask;")
            }
            w.block("if (_v instanceof java.util.Collection)") {
                w.line("int _mask = 0;")
                w.block("for (java.lang.Object o : (java.util.Collection<?>) _v)") { w.line("_mask |= describeContents(o);") }
                w.line("return _mask;")
            }
            w.block("if (_v instanceof android.os.Parcelable)") {
                w.line("return ((android.os.Parcelable) _v).describeContents();")
            }
            w.line("return 0;")
        }
    }

    private fun mayHoldDescriptor(type: AidlType): Boolean = when (type) {
        is AidlType.Parcelable, is AidlType.ListOf, is AidlType.MapOf -> true
        is AidlType.ArrayOf -> type.element is AidlType.Parcelable
        else -> false
    }

    // ---------------------------------------------------------------- enum

    /**
     * An AIDL enum becomes an annotation type holding constants of its backing integral type, the Java
     * backend's equivalent of an `@IntDef`. Unassigned members continue from the last assigned one, so
     * `A, B = 4, C` yields 0, 4, 5 even when the assigned value is an expression rather than a literal.
     */
    private fun generateEnum(decl: AidlEnum, ctx: Context): String {
        val w = JavaWriter()
        header(w, ctx.file)
        w.doc(decl.doc)
        w.block("public @interface ${decl.name}") {
            var base: String? = null
            var baseIndex = 0
            for ((index, e) in decl.enumerators.withIndex()) {
                val explicit = e.value
                val value = when {
                    explicit != null -> { base = explicit; baseIndex = index; explicit }
                    base == null -> index.toString()
                    else -> base.toIntOrNull()?.let { (it + index - baseIndex).toString() } ?: "($base) + ${index - baseIndex}"
                }
                w.doc(e.doc)
                w.line("public static final ${decl.backingType} ${e.name} = (${decl.backingType})($value);")
            }
        }
        return w.toString()
    }

    // ---------------------------------------------------------------- shared

    private fun header(w: JavaWriter, file: AidlFile) {
        w.line("/*")
        w.line(" * This file is auto-generated from AIDL. DO NOT EDIT.")
        if (file.path.isNotEmpty()) w.line(" * Source: ${file.path}")
        w.line(" */")
        if (file.packageName.isNotEmpty()) {
            w.line("package ${file.packageName};")
            w.line()
        }
    }

    private fun qualified(file: AidlFile, name: String): String =
        if (file.packageName.isEmpty()) name else "${file.packageName}.$name"
}
