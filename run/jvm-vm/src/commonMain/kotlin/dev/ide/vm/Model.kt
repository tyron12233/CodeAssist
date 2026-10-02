package dev.ide.vm

import kotlin.jvm.JvmField

internal const val ACC_PUBLIC = 0x0001
internal const val ACC_PRIVATE = 0x0002
internal const val ACC_STATIC = 0x0008
internal const val ACC_FINAL = 0x0010
internal const val ACC_SYNCHRONIZED = 0x0020
internal const val ACC_VOLATILE = 0x0040
internal const val ACC_NATIVE = 0x0100
internal const val ACC_INTERFACE = 0x0200
internal const val ACC_ABSTRACT = 0x0400
internal const val ACC_ENUM = 0x4000

// How a value of a descriptor type is held: in the primitive lane (and how wide), in the reference lane, or not at all.
internal const val K_VOID = 0
internal const val K_INT = 1 // boolean, byte, char, short, int
internal const val K_LONG = 2
internal const val K_FLOAT = 3
internal const val K_DOUBLE = 4
internal const val K_REF = 5

internal fun kindOf(descriptorChar: Char): Int = when (descriptorChar) {
    'V' -> K_VOID
    'Z', 'B', 'C', 'S', 'I' -> K_INT
    'J' -> K_LONG
    'F' -> K_FLOAT
    'D' -> K_DOUBLE
    else -> K_REF
}

internal fun wordsOf(kind: Int): Int = when (kind) {
    K_VOID -> 0
    K_LONG, K_DOUBLE -> 2
    else -> 1
}

/** The parameter descriptors of a method descriptor, in order: `(IJ[Ljava/lang/String;)V` gives `I`, `J`, `[Ljava/lang/String;`. */
internal fun parameterDescriptors(descriptor: String): List<String> {
    val out = ArrayList<String>(4)
    var i = 1
    while (descriptor[i] != ')') {
        val start = i
        while (descriptor[i] == '[') i++
        i = if (descriptor[i] == 'L') descriptor.indexOf(';', i) + 1 else i + 1
        out.add(descriptor.substring(start, i))
    }
    return out
}

internal fun returnDescriptor(descriptor: String): String = descriptor.substring(descriptor.indexOf(')') + 1)

/** `java/lang/String` to `java.lang.String`; an array keeps its descriptor form with dots, as `Class.getName` does. */
internal fun javaName(internalName: String): String = internalName.replace('/', '.')

/**
 * A class the VM has linked: either read from the class path, or defined by the JDK floor (see [JdkLibrary]),
 * or an array or primitive type the VM synthesizes.
 *
 * Instances lay out their fields as two lanes: every primitive field takes one slot of [VmObject.prims] (a
 * `long`/`double` fits in one, since the lane is a [Long]), every reference field one slot of
 * [VmObject.refs]. Slots are numbered across the whole superclass chain, so a subclass appends to its
 * parent's layout and an inherited field keeps its index.
 */
class VmClass internal constructor(
    val vm: Vm,
    /** The internal name (`java/lang/String`), an array descriptor (`[I`), or a primitive's name (`int`). */
    val name: String,
) {
    internal var access = 0
    var superClass: VmClass? = null
        internal set
    internal var interfaces: Array<VmClass> = NO_CLASSES
    internal var pool: ConstantPool? = null
    internal var parsed: ParsedClass? = null

    /** What each constant-pool entry resolved to, filled on first use by the instruction that names it. */
    internal var resolved: Array<Any?> = NO_REFS

    /** Declared methods by `name + descriptor`. */
    internal val methods = LinkedHashMap<String, VmMethod>()

    /** Declared fields by `name:descriptor`. */
    internal val fields = LinkedHashMap<String, VmField>()

    internal var primSlots = 0
    internal var refSlots = 0
    @JvmField internal var staticPrims: LongArray = NO_PRIMS
    @JvmField internal var staticRefs: Array<Any?> = NO_REFS

    /** Virtual selection results by `name + descriptor`, including misses. */
    internal val virtuals = HashMap<String, VmMethod?>()

    @JvmField internal var initState = INIT_NONE
    internal var mirror: VmObject? = null
    internal var supertypeSet: HashSet<VmClass>? = null

    /** Set for a class the JDK floor defines; its methods are Kotlin. */
    internal var isNative = false

    /**
     * Set for the JDK types whose instances are plain Kotlin values (`String` is a [String], `Integer` an
     * [Int]). `new` cannot allocate one, so it pushes an [Uninit] placeholder that the constructor replaces.
     */
    internal var valueBacked = false

    /** For an array class: the component descriptor (`I`, `Ljava/lang/String;`, `[I`). */
    internal var componentDescriptor: String? = null
    internal var componentClass: VmClass? = null

    /** For a primitive pseudo-class (`int.class`): its descriptor character. */
    internal var primitive: Char = 0.toChar()

    internal var sourceFile: String? = null
    internal var nativeStaticInit: (Call.(VmClass) -> Unit)? = null

    val isInterface: Boolean get() = access and ACC_INTERFACE != 0
    val isArray: Boolean get() = componentDescriptor != null
    val isPrimitive: Boolean get() = primitive != 0.toChar()
    val isAbstract: Boolean get() = access and ACC_ABSTRACT != 0

    /** `Class.getName()`: dots for a class, the descriptor with dots for an array, the keyword for a primitive. */
    val javaName: String get() = javaName(name)

    internal fun declaredMethod(key: String): VmMethod? = methods[key]

    override fun toString(): String = "VmClass($name)"

    internal companion object {
        val NO_CLASSES = emptyArray<VmClass>()
        val NO_REFS = arrayOfNulls<Any?>(0)
        val NO_PRIMS = LongArray(0)
    }
}

internal const val INIT_NONE = 0
internal const val INIT_RUNNING = 1
internal const val INIT_DONE = 2
internal const val INIT_FAILED = 3

class VmField internal constructor(
    val owner: VmClass,
    val name: String,
    val descriptor: String,
    internal val access: Int,
    /** Index into the object's (or the owner's static) prim or ref lane, per [isRef]. */
    internal val slot: Int,
) {
    internal val isStatic: Boolean get() = access and ACC_STATIC != 0
    internal val isRef: Boolean = descriptor[0] == 'L' || descriptor[0] == '['
    internal val words: Int = if (descriptor == "J" || descriptor == "D") 2 else 1
    internal var signature: String? = null
    override fun toString(): String = "${owner.name}.$name:$descriptor"
}

/** A method body written in Kotlin: the JDK floor's implementation of a JDK method. */
fun interface Native {
    fun invoke(call: Call)
}

class VmMethod internal constructor(
    val owner: VmClass,
    val name: String,
    val descriptor: String,
    internal val access: Int,
) {
    internal var code: ByteArray? = null
    internal var maxStack = 0
    internal var maxLocals = 0
    internal var handlers: Array<ExceptionHandler> = NO_HANDLERS
    internal var lines: IntArray = NO_LINES
    internal var native: Native? = null
    internal var signature: String? = null

    internal val isStatic: Boolean get() = access and ACC_STATIC != 0
    internal val isAbstract: Boolean get() = access and ACC_ABSTRACT != 0 && native == null
    internal val key: String = name + descriptor

    /** Words the arguments take on the operand stack, the receiver included. */
    internal val argWords: Int
    internal val retKind: Int = kindOf(descriptor[descriptor.indexOf(')') + 1])
    internal val retWords: Int = wordsOf(retKind)
    /** Kinds of the declared parameters, receiver excluded. */
    internal val paramKinds: IntArray

    init {
        val params = parameterDescriptors(descriptor)
        paramKinds = IntArray(params.size) { kindOf(params[it][0]) }
        var words = if (access and ACC_STATIC != 0) 0 else 1
        for (k in paramKinds) words += wordsOf(k)
        argWords = words
    }

    internal fun lineAt(pc: Int): Int {
        var line = -1
        var best = -1
        var i = 0
        while (i < lines.size) {
            val start = lines[i]
            if (start <= pc && start > best) { best = start; line = lines[i + 1] }
            i += 2
        }
        return line
    }

    override fun toString(): String = "${owner.name}.$name$descriptor"

    private companion object {
        val NO_HANDLERS = emptyArray<ExceptionHandler>()
        val NO_LINES = IntArray(0)
    }
}

/** An instance of a class the VM allocates: every interpreted class, and the JDK floor's subclassable ones. */
class VmObject internal constructor(@JvmField val cls: VmClass) {
    @JvmField internal val prims: LongArray = if (cls.primSlots == 0) VmClass.NO_PRIMS else LongArray(cls.primSlots)
    @JvmField internal val refs: Array<Any?> = if (cls.refSlots == 0) VmClass.NO_REFS else arrayOfNulls(cls.refSlots)

    /** State a JDK floor class keeps in Kotlin: an `ArrayList`'s elements, a `Throwable`'s message. */
    @JvmField var native: Any? = null

    override fun toString(): String = "VmObject(${cls.name})"
}

/** An array of references. Primitive arrays are plain Kotlin arrays (`int[]` is an [IntArray]). */
class VmRefArray internal constructor(@JvmField val cls: VmClass, @JvmField val data: Array<Any?>) {
    override fun toString(): String = "VmRefArray(${cls.name}, ${data.size})"
}

/**
 * An object the JDK floor implements entirely in Kotlin and never lets interpreted code subclass: an
 * iterator, a map entry, a collection view. It names the VM class that dispatches its methods.
 */
interface HostObject {
    val vmClass: VmClass
}

/** What `new` pushes for a [VmClass.valueBacked] class until its constructor produces the real value. */
internal class Uninit(val cls: VmClass)

/** An exception interpreted code threw (or the VM threw on its behalf), unwinding interpreted frames. */
internal class VmThrow(val obj: VmObject) : RuntimeException() {
    val trace = ArrayList<String>(8)
    override val message: String get() = obj.cls.name
}

/** State the JDK floor keeps for every `java.lang.Throwable`. */
internal class ThrowableState(var message: String?) {
    var cause: Any? = null
    var causeSet = false
    var trace: List<String> = emptyList()
    val suppressed = ArrayList<Any?>(0)
}

/**
 * An interpreted exception that escaped to the host. [className] is the interpreted type, [vmTrace] the
 * interpreted frames it unwound (innermost first).
 */
class VmException(
    val className: String,
    val vmMessage: String?,
    val vmTrace: List<String>,
    val cause0: VmException?,
) : RuntimeException(
    buildString {
        append(javaName(className))
        if (vmMessage != null) append(": ").append(vmMessage)
        for (frame in vmTrace) append("\n\tat ").append(frame)
        if (cause0 != null) append("\nCaused by: ").append(cause0.message)
    },
)

/**
 * Something the VM cannot do: a JDK class or member the floor does not provide, an unsupported bytecode
 * feature. Never catchable by interpreted code, so a gap is reported rather than swallowed by a
 * `catch (Throwable)`.
 */
class VmUnsupportedException(private val reason: String) : RuntimeException(reason) {
    /** The interpreted frames the gap was hit in, innermost first. */
    val vmTrace = ArrayList<String>()
    override val message: String get() = buildString {
        append(reason)
        for (frame in vmTrace) append("\n\tat ").append(frame)
    }
}
