package dev.ide.android.support.aidl

/**
 * An AIDL type resolved to the shape the Java backend marshals it as. The parser records what the source
 * said ([AidlTypeRef]); this is what it means, which is what decides the `Parcel` calls the generator
 * emits. Resolution happens in [AidlTypeResolver] against an [AidlTypeTable].
 */
sealed class AidlType {
    /** The Java type to emit in generated source (always fully qualified, as the reference compiler does). */
    abstract val java: String

    /** `void`, valid only as a return type. */
    object Void : AidlType() { override val java = "void" }

    /** `boolean`/`byte`/`char`/`int`/`long`/`float`/`double`. (AIDL has no `short`.) */
    data class Primitive(val name: String) : AidlType() { override val java = name }

    object Str : AidlType() { override val java = "java.lang.String" }

    object CharSeq : AidlType() { override val java = "java.lang.CharSequence" }

    /** `IBinder`, marshalled with `writeStrongBinder`/`readStrongBinder`. */
    object StrongBinder : AidlType() { override val java = "android.os.IBinder" }

    /** A bare `FileDescriptor`: a raw fd, distinct from the `ParcelFileDescriptor` parcelable. */
    object FileDescriptor : AidlType() { override val java = "java.io.FileDescriptor" }

    /** A type implementing `android.os.Parcelable`, whether declared in AIDL or hand-written Java. */
    data class Parcelable(val fqName: String) : AidlType() { override val java = fqName }

    /** Another AIDL `interface`, marshalled as its binder and re-wrapped with `Stub.asInterface`. */
    data class Interface(val fqName: String) : AidlType() { override val java = fqName }

    /**
     * An AIDL `enum`. The Java backend has no Java enum for it (the generated class is a holder of
     * `static final` constants), so a value of the type is just its backing integral type on the wire and
     * in signatures. [fqName] is kept for diagnostics only.
     */
    data class Enum(val fqName: String, val backing: Primitive) : AidlType() { override val java = backing.java }

    /** `List` (raw) or `List<T>`. */
    data class ListOf(val element: AidlType?) : AidlType() {
        override val java = if (element == null) "java.util.List" else "java.util.List<${element.java}>"
    }

    /** `Map` (raw) or `Map<K, V>`. The Java backend marshals both through the raw `writeMap`/`readHashMap`. */
    data class MapOf(val key: AidlType?, val value: AidlType?) : AidlType() {
        override val java =
            if (key == null || value == null) "java.util.Map" else "java.util.Map<${key.java},${value.java}>"
    }

    /** A one-dimensional array. AIDL does not have multi-dimensional arrays. */
    data class ArrayOf(val element: AidlType) : AidlType() { override val java = "${element.java}[]" }
}

/** What an AIDL name denotes. Determines whether a reference marshals as a binder or as a parcel payload. */
enum class AidlTypeKind { INTERFACE, PARCELABLE, ENUM }

/** A type the resolver knows about: declared in a `.aidl` file, listed in `framework.aidl`, or found on the classpath. */
data class AidlDeclaredType(
    val fqName: String,
    val kind: AidlTypeKind,
    /** For [AidlTypeKind.ENUM]: the integral type its constants are emitted as (`byte` unless `@Backing` says otherwise). */
    val enumBacking: String = "byte",
) {
    val simpleName: String get() = fqName.substringAfterLast('.')
    val packageName: String get() = fqName.substringBeforeLast('.', "")
}

/**
 * Everything the resolver knows about non-builtin names: the types declared by the `.aidl` files in scope
 * (the module's own AIDL roots plus its dependencies' and its AARs'), plus the SDK's preprocessed
 * `framework.aidl` when the host has one.
 *
 * A name that is in neither falls back to [probe], a bytecode look-up that answers the same question
 * `framework.aidl` would (does the class implement `android.os.Parcelable` or extend `android.os.IInterface`?)
 * by reading the compile classpath. That fallback is what keeps AIDL working on-device, where `android.jar`
 * ships as a bundled asset with no `framework.aidl` beside it.
 */
class AidlTypeTable(
    declared: Collection<AidlDeclaredType>,
    private val probe: AidlClasspathProbe = AidlClasspathProbe.NONE,
) {
    private val byFqName: Map<String, AidlDeclaredType> = declared.associateBy { it.fqName }

    /** Simple name → the declared types carrying it. More than one means the name is ambiguous unqualified. */
    private val simpleNameIndex: Map<String, List<AidlDeclaredType>> = declared.groupBy { it.simpleName }

    private val probed = HashMap<String, AidlDeclaredType?>()

    /** The declared type named [fqName], consulting the classpath probe when nothing declared it. */
    fun lookup(fqName: String): AidlDeclaredType? =
        byFqName[fqName] ?: probed.getOrPut(fqName) { probe.classify(fqName)?.let { AidlDeclaredType(fqName, it) } }

    /** Declared types (never probed ones) whose simple name is [simpleName]. */
    fun bySimpleName(simpleName: String): List<AidlDeclaredType> = simpleNameIndex[simpleName].orEmpty()

    /** Every declared type, for callers that enumerate (the editor's synthetic-class provider). */
    val all: Collection<AidlDeclaredType> get() = byFqName.values

    companion object {
        /** The types every AIDL file may reference without importing anything. */
        private val IMPLICIT = listOf(
            AidlDeclaredType("android.os.ParcelFileDescriptor", AidlTypeKind.PARCELABLE),
            AidlDeclaredType("android.os.PersistableBundle", AidlTypeKind.PARCELABLE),
            AidlDeclaredType("android.os.Bundle", AidlTypeKind.PARCELABLE),
        )

        /** Build a table from parsed AIDL files (`declarations` of every kind) plus optional extras. */
        fun of(files: Collection<AidlFile>, extra: Collection<AidlDeclaredType> = emptyList(), probe: AidlClasspathProbe = AidlClasspathProbe.NONE) =
            AidlTypeTable(IMPLICIT + files.flatMap { declaredTypesOf(it) } + extra, probe)

        /** The types [file] declares, with names qualified by its package (a preprocessed file's are already qualified). */
        fun declaredTypesOf(file: AidlFile): List<AidlDeclaredType> = file.declarations.map { decl ->
            val fq = if ('.' in decl.name || file.packageName.isEmpty()) decl.name else "${file.packageName}.${decl.name}"
            when (decl) {
                is AidlInterface -> AidlDeclaredType(fq, AidlTypeKind.INTERFACE)
                is AidlEnum -> AidlDeclaredType(fq, AidlTypeKind.ENUM, decl.backingType)
                // Parcelable declarations, structured parcelables and unions all marshal as parcel payloads.
                else -> AidlDeclaredType(fq, AidlTypeKind.PARCELABLE)
            }
        }
    }
}

/**
 * Resolves the type references of one file, so it carries that file's package and imports, which is what
 * an unqualified name is interpreted against.
 *
 * Unresolvable and unsupported types are reported through [report] and resolved to a best guess rather than
 * aborting, so one bad signature yields one diagnostic instead of hiding every other problem in the file.
 */
class AidlTypeResolver(
    private val table: AidlTypeTable,
    private val packageName: String,
    imports: List<String>,
    private val file: String = "",
    private val report: (AidlDiagnostic) -> Unit = {},
) {
    /** Simple name → fully-qualified, from the file's `import` statements. */
    private val imported: Map<String, String> = imports.associateBy { it.substringAfterLast('.') }

    /** Resolve [ref]; [asReturn] permits `void`, which is never a valid parameter or field type. */
    fun resolve(ref: AidlTypeRef, asReturn: Boolean = false): AidlType {
        if (ref.arrayDims > 1) {
            reportError(ref.pos, "multi-dimensional arrays are not supported in AIDL: '$ref'")
            return AidlType.ArrayOf(AidlType.Primitive("int"))
        }
        val base = resolveBase(ref, asReturn && ref.arrayDims == 0)
        return if (ref.arrayDims == 1) asArray(base, ref) else base
    }

    private fun asArray(element: AidlType, ref: AidlTypeRef): AidlType {
        val supported = when (element) {
            is AidlType.Primitive -> element.name != "short"
            // An enum array marshals as an array of its backing integral type.
            is AidlType.Enum -> true
            AidlType.Str, AidlType.StrongBinder -> true
            is AidlType.Parcelable -> true
            else -> false
        }
        if (!supported) reportError(ref.pos, "'${ref.name}[]' is not a supported AIDL array type")
        return AidlType.ArrayOf(element)
    }

    private fun resolveBase(ref: AidlTypeRef, allowVoid: Boolean): AidlType {
        BUILTINS[ref.name]?.let { builtin ->
            if (builtin == AidlType.Void && !allowVoid) reportError(ref.pos, "'void' is only valid as a return type")
            if (builtin is AidlType.Primitive && builtin.name == "short") {
                reportError(ref.pos, "AIDL has no 'short' type; use 'int'")
            }
            return generify(builtin, ref)
        }
        val declared = resolveName(ref)
        if (declared == null) {
            reportError(
                ref.pos,
                "unknown type '${ref.name}'. Declare it in a .aidl file (`parcelable ${ref.name.substringAfterLast('.')};`) " +
                    "and import it, or check the import spelling.",
            )
            return AidlType.Parcelable(qualify(ref.name))
        }
        return when (declared.kind) {
            AidlTypeKind.INTERFACE -> AidlType.Interface(declared.fqName)
            AidlTypeKind.PARCELABLE -> AidlType.Parcelable(declared.fqName)
            AidlTypeKind.ENUM -> AidlType.Enum(declared.fqName, AidlType.Primitive(declared.enumBacking))
        }
    }

    /** Attach type arguments to the two generic builtins; everything else rejects them. */
    private fun generify(builtin: AidlType, ref: AidlTypeRef): AidlType = when {
        builtin is AidlType.ListOf && ref.typeArgs.size == 1 -> AidlType.ListOf(resolve(ref.typeArgs[0]))
        builtin is AidlType.MapOf && ref.typeArgs.size == 2 ->
            AidlType.MapOf(resolve(ref.typeArgs[0]), resolve(ref.typeArgs[1]))
        ref.typeArgs.isEmpty() -> builtin
        else -> {
            reportError(ref.pos, "'${ref.name}' does not take ${ref.typeArgs.size} type argument(s)")
            builtin
        }
    }

    /**
     * An unqualified name resolves against, in order: the file's imports, its own package, then any uniquely
     * named type in scope. A qualified name is looked up directly. When nothing matches and the name looks
     * like a framework type, fall back to AIDL's own naming convention (`IFoo` is an interface, anything else
     * a parcelable) with a warning: a wrong guess is better than refusing to build against an SDK whose
     * `framework.aidl` the host does not ship.
     */
    private fun resolveName(ref: AidlTypeRef): AidlDeclaredType? {
        if ('.' in ref.name) return table.lookup(ref.name) ?: guess(ref, ref.name)
        imported[ref.name]?.let { fq -> return table.lookup(fq) ?: guess(ref, fq) }
        if (packageName.isNotEmpty()) table.lookup("$packageName.${ref.name}")?.let { return it }
        val candidates = table.bySimpleName(ref.name)
        if (candidates.size == 1) return candidates.single()
        if (candidates.size > 1) {
            reportError(ref.pos, "'${ref.name}' is ambiguous: ${candidates.joinToString { it.fqName }}. Import the one you mean.")
            return candidates.first()
        }
        return null
    }

    private fun guess(ref: AidlTypeRef, fqName: String): AidlDeclaredType? {
        if (!fqName.startsWith("android.")) return null
        val simple = fqName.substringAfterLast('.')
        val kind = if (simple.length > 1 && simple[0] == 'I' && simple[1].isUpperCase()) {
            AidlTypeKind.INTERFACE
        } else {
            AidlTypeKind.PARCELABLE
        }
        report(
            AidlDiagnostic(
                AidlSeverity.WARNING,
                "'$fqName' was not found in the SDK's framework.aidl or on the compile classpath; " +
                    "assuming it is ${if (kind == AidlTypeKind.INTERFACE) "an interface" else "a parcelable"} from its name.",
                file, ref.pos,
            )
        )
        return AidlDeclaredType(fqName, kind)
    }

    /** Qualify a bare name with the file's package, for a placeholder after an unresolved-type error. */
    private fun qualify(name: String): String =
        if ('.' in name || packageName.isEmpty()) name else "$packageName.$name"

    private fun reportError(pos: AidlPos, message: String) =
        report(AidlDiagnostic(AidlSeverity.ERROR, message, file, pos))

    private companion object {
        /** Names every AIDL file resolves without an import. Both the bare and `java.*`-qualified spellings work. */
        val BUILTINS: Map<String, AidlType> = buildMap {
            put("void", AidlType.Void)
            for (p in listOf("boolean", "byte", "char", "short", "int", "long", "float", "double")) {
                put(p, AidlType.Primitive(p))
            }
            put("String", AidlType.Str); put("java.lang.String", AidlType.Str)
            put("CharSequence", AidlType.CharSeq); put("java.lang.CharSequence", AidlType.CharSeq)
            put("List", AidlType.ListOf(null)); put("java.util.List", AidlType.ListOf(null))
            put("Map", AidlType.MapOf(null, null)); put("java.util.Map", AidlType.MapOf(null, null))
            put("IBinder", AidlType.StrongBinder); put("android.os.IBinder", AidlType.StrongBinder)
            put("FileDescriptor", AidlType.FileDescriptor); put("java.io.FileDescriptor", AidlType.FileDescriptor)
        }
    }
}
