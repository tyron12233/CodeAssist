package dev.ide.vm.jdk

import dev.ide.vm.*

/** `List<Color>` as reflection hands it out: the raw class, its type arguments, and its owner. */
internal class HostParameterizedType(override val vmClass: VmClass, val raw: Any, val arguments: List<Any>, val owner: Any?) : HostObject {
    override fun equals(other: Any?) = other is HostParameterizedType && other.raw == raw && other.arguments == arguments && other.owner == owner
    override fun hashCode() = raw.hashCode() * 31 + arguments.hashCode()
}

internal class HostTypeVariable(override val vmClass: VmClass, val name: String, val bounds: List<Any>) : HostObject {
    override fun equals(other: Any?) = other is HostTypeVariable && other.name == name
    override fun hashCode() = name.hashCode()
}

internal class HostWildcardType(override val vmClass: VmClass, val upper: List<Any>, val lower: List<Any>) : HostObject {
    override fun equals(other: Any?) = other is HostWildcardType && other.upper == upper && other.lower == lower
    override fun hashCode() = upper.hashCode() xor lower.hashCode()
}

internal class HostGenericArrayType(override val vmClass: VmClass, val component: Any) : HostObject {
    override fun equals(other: Any?) = other is HostGenericArrayType && other.component == component
    override fun hashCode() = component.hashCode()
}

/**
 * Generic signatures (JVMS 4.7.9.1) read into `java.lang.reflect.Type`s: a `Class` mirror where a type
 * has no arguments, and the four generic kinds otherwise. Type variables keep their name only; nothing that
 * reads them through reflection here needs more.
 */
internal class SignatureParser(private val vm: Vm, private val s: String) {
    private var at = 0

    /** A method signature's parameter types and return type. */
    fun method(): Pair<List<Any>, Any> {
        skipTypeParameters()
        expect('(')
        val params = ArrayList<Any>()
        while (s[at] != ')') params.add(type())
        at++
        val ret = if (s[at] == 'V') { at++; vm.mirror(vm.primitiveClass('V')) } else type()
        return params to ret
    }

    /** A class signature's superclass and interfaces. */
    fun classSupertypes(): List<Any> {
        skipTypeParameters()
        val out = ArrayList<Any>()
        while (at < s.length) out.add(type())
        return out
    }

    fun type(): Any = when (val c = s[at]) {
        'L' -> classType()
        'T' -> {
            at++
            val end = s.indexOf(';', at)
            val name = s.substring(at, end)
            at = end + 1
            HostTypeVariable(vm.loadClass("java/lang/reflect/HostTypeVariable"), name, listOf(vm.mirror(vm.objectClass)))
        }
        '[' -> {
            at++
            val component = type()
            if (component is VmObject) {
                val cls = component.native as VmClass
                vm.mirror(vm.loadClass(if (cls.isPrimitive) "[${cls.primitive}" else if (cls.isArray) "[${cls.name}" else "[L${cls.name};"))
            } else HostGenericArrayType(vm.loadClass("java/lang/reflect/HostGenericArrayType"), component)
        }
        else -> { at++; vm.mirror(vm.primitiveClass(c)) }
    }

    private fun classType(): Any {
        expect('L')
        var name = StringBuilder()
        var result: Any? = null
        var owner: Any? = null
        while (true) {
            while (s[at] != '<' && s[at] != ';' && s[at] != '.') name.append(s[at++])
            val raw = vm.mirror(vm.classForDescriptor("L$name;"))
            val args = ArrayList<Any>()
            if (s[at] == '<') {
                at++
                while (s[at] != '>') args.add(typeArgument())
                at++
            }
            result = if (args.isEmpty() && owner == null) raw
            else HostParameterizedType(vm.loadClass("java/lang/reflect/HostParameterizedType"), raw, args, owner)
            if (s[at] == '.') {
                at++
                owner = result
                name = StringBuilder(name).append('$')
                continue
            }
            at++ // ';'
            return result!!
        }
    }

    private fun typeArgument(): Any = when (s[at]) {
        '*' -> { at++; HostWildcardType(vm.loadClass("java/lang/reflect/HostWildcardType"), listOf(vm.mirror(vm.objectClass)), emptyList()) }
        '+' -> { at++; HostWildcardType(vm.loadClass("java/lang/reflect/HostWildcardType"), listOf(type()), emptyList()) }
        '-' -> { at++; HostWildcardType(vm.loadClass("java/lang/reflect/HostWildcardType"), listOf(vm.mirror(vm.objectClass)), listOf(type())) }
        else -> type()
    }

    private fun skipTypeParameters() {
        if (at >= s.length || s[at] != '<') return
        var depth = 0
        do {
            when (s[at]) { '<' -> depth++; '>' -> depth-- }
            at++
        } while (depth > 0)
    }

    private fun expect(c: Char) {
        if (s[at] != c) throw IllegalArgumentException("bad signature $s at $at")
        at++
    }
}

private fun Vm.typeArray(types: List<Any>): VmRefArray = newRefArray("java/lang/reflect/Type", types.toTypedArray())

/** The generic parameter types of [m], from its signature when it has one, else its erased descriptor. */
internal fun Vm.genericParameterTypes(m: VmMethod): List<Any> {
    val erased = parameterDescriptors(m.descriptor).map { mirror(classForDescriptor(it)) }
    val signature = m.signature ?: return erased
    val generic = runCatching { SignatureParser(this, signature).method().first }.getOrNull() ?: return erased
    // A constructor of an inner or enum class carries synthetic leading parameters its signature omits.
    return if (generic.size == erased.size) generic else erased.take(erased.size - generic.size) + generic
}

internal fun Vm.genericReturnType(m: VmMethod): Any {
    val erased = mirror(classForDescriptor(returnDescriptor(m.descriptor).let { if (it == "V") "V" else it }))
    val signature = m.signature ?: return erased
    return runCatching { SignatureParser(this, signature).method().second }.getOrNull() ?: erased
}

internal fun Vm.genericFieldType(f: VmField): Any {
    val erased = mirror(classForDescriptor(f.descriptor))
    val signature = f.signature ?: return erased
    return runCatching { SignatureParser(this, signature).type() }.getOrNull() ?: erased
}

/** A class's generic superclass and interfaces, in that order, or null when it has no signature. */
internal fun Vm.genericSupertypes(cls: VmClass): List<Any>? {
    val signature = cls.parsed?.signature ?: return null
    return runCatching { SignatureParser(this, signature).classSupertypes() }.getOrNull()
}

internal fun ClassDefs.registerGenerics() {
    define("java/lang/reflect/HostParameterizedType") {
        implements("java/lang/reflect/ParameterizedType")
        fun Call.p() = r(0) as HostParameterizedType
        method("getRawType", "()Ljava/lang/reflect/Type;") { retRef(p().raw) }
        method("getActualTypeArguments", "()[Ljava/lang/reflect/Type;") { retRef(vm.typeArray(p().arguments)) }
        method("getOwnerType", "()Ljava/lang/reflect/Type;") { retRef(p().owner) }
        method("getTypeName", "()Ljava/lang/String;") { retRef(vm.typeName(p())) }
        method("toString", "()Ljava/lang/String;") { retRef(vm.typeName(p())) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(p() == r(1)) }
        method("hashCode", "()I") { ret(p().hashCode()) }
    }
    define("java/lang/reflect/HostTypeVariable") {
        implements("java/lang/reflect/TypeVariable")
        fun Call.v() = r(0) as HostTypeVariable
        method("getName", "()Ljava/lang/String;") { retRef(v().name) }
        method("getBounds", "()[Ljava/lang/reflect/Type;") { retRef(vm.typeArray(v().bounds)) }
        method("getGenericDeclaration", "()Ljava/lang/reflect/GenericDeclaration;") { retRef(null) }
        method("getTypeName", "()Ljava/lang/String;") { retRef(v().name) }
        method("toString", "()Ljava/lang/String;") { retRef(v().name) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(v() == r(1)) }
        method("hashCode", "()I") { ret(v().hashCode()) }
    }
    define("java/lang/reflect/HostWildcardType") {
        implements("java/lang/reflect/WildcardType")
        fun Call.w() = r(0) as HostWildcardType
        method("getUpperBounds", "()[Ljava/lang/reflect/Type;") { retRef(vm.typeArray(w().upper)) }
        method("getLowerBounds", "()[Ljava/lang/reflect/Type;") { retRef(vm.typeArray(w().lower)) }
        method("getTypeName", "()Ljava/lang/String;") { retRef(vm.typeName(w())) }
        method("toString", "()Ljava/lang/String;") { retRef(vm.typeName(w())) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(w() == r(1)) }
        method("hashCode", "()I") { ret(w().hashCode()) }
    }
    define("java/lang/reflect/HostGenericArrayType") {
        implements("java/lang/reflect/GenericArrayType")
        fun Call.a() = r(0) as HostGenericArrayType
        method("getGenericComponentType", "()Ljava/lang/reflect/Type;") { retRef(a().component) }
        method("getTypeName", "()Ljava/lang/String;") { retRef(vm.typeName(a())) }
        method("toString", "()Ljava/lang/String;") { retRef(vm.typeName(a())) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(a() == r(1)) }
        method("hashCode", "()I") { ret(a().hashCode()) }
    }
}

/** `Type.getTypeName()`, as the JDK formats it. */
internal fun Vm.typeName(t: Any?): String = when (t) {
    is VmObject -> ((t.native as? VmClass)?.typeName()) ?: vmToString(t)
    is HostParameterizedType -> typeName(t.raw) + t.arguments.joinToString(", ", "<", ">") { typeName(it) }
    is HostTypeVariable -> t.name
    is HostWildcardType -> when {
        t.lower.isNotEmpty() -> "? super " + typeName(t.lower[0])
        t.upper.isEmpty() || (t.upper[0] as? VmObject)?.native == objectClass -> "?"
        else -> "? extends " + typeName(t.upper[0])
    }
    is HostGenericArrayType -> typeName(t.component) + "[]"
    else -> vmToString(t)
}
