package dev.ide.lang.jdt.compile

import java.security.MessageDigest
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.ClassSignature
import org.eclipse.jdt.internal.compiler.env.EnumConstantSignature
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation
import org.eclipse.jdt.internal.compiler.impl.Constant

/**
 * The part of a compiled `.class` that another source file can depend on, as a digest. Two compilations of a
 * class have the same snapshot exactly when no other file compiled against the first could compile
 * differently against the second, so an edit that keeps it is invisible outside its own source file.
 *
 * Covered: the class header (modifiers, name, superclass, interfaces, generic signature, permitted subtypes,
 * enclosing and member types, record components), its annotations, and every non-private, non-synthetic
 * field and method with its signature, thrown types, annotations and annotation default. Field constant values
 * are included, because the compiler copies a `static final` constant into every class that reads it. Method
 * bodies, private members and compiler-generated members are not.
 */
internal object JavaClassAbi {

    /** What [read] learns about one class file. */
    class Snapshot(
        /** Hex digest of the class's API (see the class doc). */
        val abi: String,
        /** True for an anonymous, local or private nested class: nothing outside its own source can name it. */
        val isLocal: Boolean,
        /** The class's simple name, for spotting a new class another file might now resolve to. */
        val simpleName: String,
    )

    /** The snapshot of [classBytes], or null when they are not a readable class file. */
    fun read(classBytes: ByteArray): Snapshot? {
        val reader = runCatching { ClassFileReader(classBytes, null, true) }.getOrNull() ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        val out = Sink(md)

        out.put("C", reader.modifiers and ClassFileConstants.AccSuper.inv())
        out.put(reader.name)
        out.put(reader.superclassName)
        reader.interfaceNames?.forEach { out.put(it) }
        out.put("G"); out.put(reader.genericSignature)
        out.put("E"); out.put(reader.enclosingTypeName)
        reader.permittedSubtypesNames?.map { String(it) }?.sorted()?.forEach { out.put("P"); out.put(it) }
        reader.memberTypes.orEmpty()
            .filter { it.modifiers and ClassFileConstants.AccPrivate == 0 }
            .map { "${String(it.name)}:${it.modifiers}" }
            .sorted()
            .forEach { out.put("M"); out.put(it) }
        annotations(out, reader.annotations)
        if (reader.isRecord) {
            reader.recordComponents?.forEach { c ->
                out.put("R"); out.put(c.name); out.put(c.typeName); out.put(c.genericSignature)
            }
        }

        reader.fields.orEmpty()
            .filter { visible(it.modifiers) }
            .sortedBy { String(it.name) }
            .forEach { f ->
                out.put("F", f.modifiers)
                out.put(f.name)
                out.put(f.typeName)
                out.put(f.genericSignature)
                out.put(constant(f.constant))
                annotations(out, f.annotations)
            }

        reader.methods.orEmpty()
            .filter { visible(it.modifiers) && !it.isClinit }
            .sortedBy { String(it.selector) + String(it.methodDescriptor) }
            .forEach { m ->
                out.put("m", m.modifiers)
                out.put(m.selector)
                out.put(m.methodDescriptor)
                out.put(m.genericSignature)
                m.exceptionTypeNames?.map { String(it) }?.sorted()?.forEach { out.put("T"); out.put(it) }
                annotations(out, m.annotations)
                for (i in 0 until m.annotatedParametersCount) annotations(out, m.getParameterAnnotations(i, reader.fileName))
                m.defaultValue?.let { out.put("D"); out.put(value(it)) }
            }

        val name = String(reader.name)
        val simple = reader.sourceName?.let { String(it) }?.takeIf { it.isNotEmpty() }
            ?: name.substringAfterLast('/').substringAfterLast('$')
        return Snapshot(
            abi = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 32),
            isLocal = reader.isAnonymous || reader.isLocal ||
                (reader.isNestedType && reader.modifiers and ClassFileConstants.AccPrivate != 0),
            simpleName = simple,
        )
    }

    private fun visible(modifiers: Int): Boolean =
        modifiers and ClassFileConstants.AccPrivate == 0 && modifiers and ClassFileConstants.AccSynthetic == 0

    private fun annotations(out: Sink, annotations: Array<out IBinaryAnnotation>?) {
        annotations?.map { annotation(it) }?.sorted()?.forEach { out.put("@"); out.put(it) }
    }

    private fun annotation(a: IBinaryAnnotation): String =
        String(a.typeName) + a.elementValuePairs.orEmpty()
            .map { "${String(it.name)}=${value(it.value)}" }
            .sorted()
            .joinToString(",", "(", ")")

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Constant -> constant(v)
        is ClassSignature -> "class " + String(v.typeName)
        is EnumConstantSignature -> String(v.typeName) + "." + String(v.enumConstantName)
        is IBinaryAnnotation -> annotation(v)
        is Array<*> -> v.joinToString(",", "{", "}") { value(it) }
        else -> v.toString()
    }

    private fun constant(c: Constant?): String =
        if (c == null || c === Constant.NotAConstant) "" else "${c.typeID()}:${c.stringValue()}"

    private class Sink(private val md: MessageDigest) {
        fun put(s: String?) {
            if (s != null) md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0)
        }

        fun put(chars: CharArray?) = put(chars?.let { String(it) })

        fun put(tag: String, flags: Int) {
            put(tag)
            put(flags.toString())
        }
    }
}
