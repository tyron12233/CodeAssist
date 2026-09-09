package dev.ide.android.support.aidl

/**
 * How each [AidlType] crosses a `Parcel`: the whole of AIDL's wire format, in one place.
 *
 * The generator asks for four things per type, which is everything the Java backend needs:
 *
 *  - [write] puts a value into a parcel (the proxy filling `_aidl_data`, the stub filling `_aidl_reply`).
 *  - [read] takes a fresh value out of a parcel into a declared local.
 *  - [readBack] reads a parcel into an object the caller already holds: how `out`/`inout` results get home.
 *  - [allocateOut] is the stub's side of `out`: it builds the object the implementation will fill in.
 *
 * Only public `Parcel` API is emitted. That rules out `FileDescriptor`, whose reference-compiler marshalling
 * (`writeRawFileDescriptor`/`readRawFileDescriptor`) is platform-hidden and would not compile against
 * `android.jar`; [AidlJavaGenerator] reports that as an error pointing at `ParcelFileDescriptor` instead.
 * Booleans go over the wire as ints rather than through `Parcel.writeBoolean`, which only exists from API 29.
 */
internal object AidlMarshalling {

    /** `PARCELABLE_WRITE_RETURN_VALUE`: the flag a stub writes return values and `out` results with. */
    const val RETURN_FLAGS = "android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE"

    /** The flag a proxy writes call arguments with. */
    const val ARG_FLAGS = "0"

    private const val CLASS_LOADER = "(java.lang.ClassLoader)this.getClass().getClassLoader()"

    /** Emit statements writing [expr] (a value of [type]) into [parcel]. */
    fun write(w: JavaWriter, parcel: String, expr: String, type: AidlType, flags: String) {
        when (type) {
            is AidlType.Primitive -> w.line("$parcel.${primitiveWrite(type.name, expr)};")
            is AidlType.Enum -> w.line("$parcel.${primitiveWrite(type.backing.name, expr)};")
            AidlType.Str -> w.line("$parcel.writeString($expr);")
            AidlType.StrongBinder -> w.line("$parcel.writeStrongBinder($expr);")
            is AidlType.Interface -> w.line("$parcel.writeStrongInterface($expr);")
            AidlType.CharSeq -> nullable(w, parcel, expr) {
                w.line("android.text.TextUtils.writeToParcel($expr, $parcel, $flags);")
            }
            is AidlType.Parcelable -> nullable(w, parcel, expr) {
                w.line("$expr.writeToParcel($parcel, $flags);")
            }
            is AidlType.ListOf -> w.line("$parcel.${listWrite(type.element)}($expr);")
            is AidlType.MapOf -> w.line("$parcel.writeMap($expr);")
            is AidlType.ArrayOf -> when (val e = type.element) {
                is AidlType.Parcelable -> w.line("$parcel.writeTypedArray($expr, $flags);")
                else -> w.line("$parcel.${arrayWrite(e)}($expr);")
            }
            AidlType.Void, AidlType.FileDescriptor -> Unit  // rejected before reaching the generator
        }
    }

    /** Emit statements reading a value of [type] out of [parcel] and assigning it to the local [target]. */
    fun read(w: JavaWriter, parcel: String, target: String, type: AidlType) {
        when (type) {
            is AidlType.Primitive -> w.line("$target = ${primitiveRead(type.name, parcel)};")
            is AidlType.Enum -> w.line("$target = ${primitiveRead(type.backing.name, parcel)};")
            AidlType.Str -> w.line("$target = $parcel.readString();")
            AidlType.StrongBinder -> w.line("$target = $parcel.readStrongBinder();")
            is AidlType.Interface -> w.line("$target = ${type.fqName}.Stub.asInterface($parcel.readStrongBinder());")
            AidlType.CharSeq -> ifPresent(w, parcel, target) {
                w.line("$target = android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel($parcel);")
            }
            is AidlType.Parcelable -> ifPresent(w, parcel, target) {
                w.line("$target = ${type.fqName}.CREATOR.createFromParcel($parcel);")
            }
            is AidlType.ListOf -> w.line("$target = $parcel.${listCreate(type.element)};")
            is AidlType.MapOf -> w.line("$target = $parcel.readHashMap($CLASS_LOADER);")
            is AidlType.ArrayOf -> when (val e = type.element) {
                is AidlType.Parcelable -> w.line("$target = $parcel.createTypedArray(${e.fqName}.CREATOR);")
                else -> w.line("$target = $parcel.${arrayCreate(e)}();")
            }
            AidlType.Void, AidlType.FileDescriptor -> Unit
        }
    }

    /**
     * Emit statements reading [parcel] back into the object [expr] already refers to: the `out`/`inout`
     * return path, where the value has to land in the caller's own array/list/parcelable rather than a new one.
     */
    fun readBack(w: JavaWriter, parcel: String, expr: String, type: AidlType) {
        when (type) {
            is AidlType.Parcelable -> {
                w.block("if ((0!=$parcel.readInt()))") { w.line("$expr.readFromParcel($parcel);") }
            }
            is AidlType.ListOf -> when (val e = type.element) {
                is AidlType.Parcelable -> w.line("$parcel.readTypedList($expr, ${e.fqName}.CREATOR);")
                AidlType.Str -> w.line("$parcel.readStringList($expr);")
                AidlType.StrongBinder -> w.line("$parcel.readBinderList($expr);")
                else -> w.line("$parcel.readList($expr, $CLASS_LOADER);")
            }
            is AidlType.MapOf -> w.line("$parcel.readMap($expr, $CLASS_LOADER);")
            is AidlType.ArrayOf -> when (val e = type.element) {
                is AidlType.Parcelable -> w.line("$parcel.readTypedArray($expr, ${e.fqName}.CREATOR);")
                else -> w.line("$parcel.${arrayReadBack(e)}($expr);")
            }
            else -> Unit  // only arrays, lists, maps and parcelables can be `out`/`inout`
        }
    }

    /**
     * The stub's allocation of an `out` parameter, before the implementation is called. The proxy sent only an
     * array's length (nothing at all for a list or parcelable), so the object the implementation fills is made
     * here, which is why an `out` parcelable needs a public no-argument constructor.
     */
    fun allocateOut(w: JavaWriter, parcel: String, target: String, type: AidlType) {
        when (type) {
            is AidlType.ArrayOf -> w.line("$target = new ${type.element.java}[$parcel.readInt()];")
            is AidlType.ListOf -> w.line("$target = new java.util.ArrayList${if (type.element == null) "" else "<${type.element.java}>"}();")
            is AidlType.Parcelable -> w.line("$target = new ${type.fqName}();")
            else -> Unit
        }
    }

    /**
     * The proxy's side of an `out` parameter: an array's length has to travel so the stub can allocate one the
     * right size. Lists and parcelables send nothing; the stub makes an empty one.
     */
    fun writeOutHeader(w: JavaWriter, parcel: String, expr: String, type: AidlType) {
        if (type is AidlType.ArrayOf) w.line("$parcel.writeInt($expr.length);")
    }

    /** True when [type] can carry data back out of a call: the only types `out`/`inout` accept. */
    fun supportsOut(type: AidlType): Boolean =
        type is AidlType.ArrayOf || type is AidlType.ListOf || type is AidlType.Parcelable

    /** The value a `Default` implementation returns for [type]. */
    fun defaultValue(type: AidlType): String = when (type) {
        is AidlType.Primitive -> primitiveDefault(type.name)
        is AidlType.Enum -> primitiveDefault(type.backing.name)
        else -> "null"
    }

    // ---------------------------------------------------------------- per-shape tables

    private fun primitiveWrite(name: String, expr: String): String = when (name) {
        // Parcel.writeBoolean only exists from API 29; the int form works on every supported level.
        "boolean" -> "writeInt((($expr)?(1):(0)))"
        "byte" -> "writeByte($expr)"
        "char" -> "writeInt(((int)$expr))"
        "int" -> "writeInt($expr)"
        "long" -> "writeLong($expr)"
        "float" -> "writeFloat($expr)"
        "double" -> "writeDouble($expr)"
        else -> "writeInt($expr)"
    }

    private fun primitiveRead(name: String, parcel: String): String = when (name) {
        "boolean" -> "(0!=$parcel.readInt())"
        "byte" -> "$parcel.readByte()"
        "char" -> "((char)$parcel.readInt())"
        "int" -> "$parcel.readInt()"
        "long" -> "$parcel.readLong()"
        "float" -> "$parcel.readFloat()"
        "double" -> "$parcel.readDouble()"
        else -> "$parcel.readInt()"
    }

    private fun primitiveDefault(name: String): String = when (name) {
        "boolean" -> "false"
        "byte" -> "(byte)0"
        "char" -> "(char)0"
        "long" -> "0L"
        "float" -> "0.0f"
        "double" -> "0.0"
        else -> "0"
    }

    private fun listWrite(element: AidlType?): String = when (element) {
        AidlType.Str -> "writeStringList"
        AidlType.StrongBinder -> "writeBinderList"
        is AidlType.Parcelable -> "writeTypedList"
        else -> "writeList"
    }

    private fun listCreate(element: AidlType?): String = when (element) {
        AidlType.Str -> "createStringArrayList()"
        AidlType.StrongBinder -> "createBinderArrayList()"
        is AidlType.Parcelable -> "createTypedArrayList(${element.fqName}.CREATOR)"
        else -> "readArrayList($CLASS_LOADER)"
    }

    private fun arrayWrite(element: AidlType): String = "write${arraySuffix(element)}Array"
    private fun arrayCreate(element: AidlType): String = "create${arraySuffix(element)}Array"
    private fun arrayReadBack(element: AidlType): String = "read${arraySuffix(element)}Array"

    private fun arraySuffix(element: AidlType): String = when (element) {
        AidlType.Str -> "String"
        AidlType.StrongBinder -> "Binder"
        is AidlType.Enum -> primitiveArraySuffix(element.backing.name)
        is AidlType.Primitive -> primitiveArraySuffix(element.name)
        else -> "Int"
    }

    private fun primitiveArraySuffix(name: String): String = name.replaceFirstChar { it.uppercase() }

    /** `if (v != null) { parcel.writeInt(1); …body… } else { parcel.writeInt(0); }`: the nullable-object framing. */
    private fun nullable(w: JavaWriter, parcel: String, expr: String, body: () -> Unit) {
        w.block("if (($expr!=null))") {
            w.line("$parcel.writeInt(1);")
            body()
        }
        w.blockElse { w.line("$parcel.writeInt(0);") }
    }

    /** The read half of [nullable]: present ⇒ create, absent ⇒ null. */
    private fun ifPresent(w: JavaWriter, parcel: String, target: String, body: () -> Unit) {
        w.block("if ((0!=$parcel.readInt()))") { body() }
        w.blockElse { w.line("$target = null;") }
    }
}
