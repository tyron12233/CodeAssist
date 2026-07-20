package dev.ide.jvm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * A class the interpreter runs, parsed from class-file bytecode into its methods, fields, and supertype links.
 * Only classes the [Vm]'s policy marks interpretable are represented here; other classes are reached through
 * the [NativeBridge].
 *
 * [name], [superName], and [interfaces] are internal names (`dev/ide/jvm/Foo`). [statics] holds the static
 * field values, defaulted at load and populated by the class initializer, which [initialized] guards so it
 * runs once, on first use.
 */
internal class VmClass(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val methods: List<MethodNode>,
    fields: List<FieldNode>,
) {
    /** Descriptors of the declared instance fields, by name, used to default an instance's fields on allocation. */
    val instanceFieldDescs: Map<String, String> =
        fields.filter { it.access and Opcodes.ACC_STATIC == 0 }.associate { it.name to it.desc }
    val staticFieldDescs: Map<String, String> =
        fields.filter { it.access and Opcodes.ACC_STATIC != 0 }.associate { it.name to it.desc }

    /** Static field values: defaulted from the descriptor, then seeded from any `ConstantValue` attribute (a
     *  `static final` primitive/String constant), and finally set by the class initializer. The JVM assigns a
     *  `ConstantValue` field during class preparation — before `<clinit>`, which carries no assignment for it —
     *  so honoring it here is required: a class compiled against a NON-final field (emitting a `getstatic`)
     *  reading a provider that declares it `final` (e.g. a library reading a regenerated `R` constant) would
     *  otherwise see the descriptor default instead of the constant. ASM exposes the attribute as
     *  [FieldNode.value] (an Integer for int/short/byte/char/boolean, else Long/Float/Double/String), which
     *  already matches the interpreter's value conventions. */
    val statics: HashMap<String, Any?> = HashMap<String, Any?>().apply {
        fields.filter { it.access and Opcodes.ACC_STATIC != 0 }
            .forEach { put(it.name, it.value ?: Descriptors.defaultValue(it.desc)) }
    }

    var initialized: Boolean = false

    /** The method declared on this class matching [name] and [descriptor], or null if it declares no such method. */
    fun declaredMethod(name: String, descriptor: String): MethodNode? =
        methods.firstOrNull { it.name == name && it.desc == descriptor }
}

/**
 * An instance of an interpreted [VmClass]. Field values are held in [fields], defaulted across the whole
 * instance-field chain when the object is allocated. A [VmObject] is not an instance of the real [vmClass]
 * (that class is never loaded by the host), so it cannot be passed to a platform method that expects that
 * type; passing an interpreted object to platform code requires a real peer object, which the bridge rejects
 * until that path is implemented.
 */
internal class VmObject(val vmClass: VmClass) {
    val fields: HashMap<String, Any?> = HashMap()

    /** The real peer object platform code holds for this instance, created on demand when the object crosses to
     *  platform code or an interpreted `super` call needs the real supertype. Null until then. */
    var peer: Any? = null

    override fun toString(): String = "VmObject(${vmClass.name})"
}

/**
 * An array created by interpreted code. [elementDescriptor] is the element type descriptor (`I`,
 * `Ljava/lang/String;`, or `[I` for the inner arrays of a two-dimensional array). Elements are stored in
 * [data] under the interpreter's value conventions (see [Descriptors]).
 */
internal class VmArray(val elementDescriptor: String, val data: Array<Any?>) {
    val length: Int get() = data.size

    /** A cached real Java array used when this array crosses to platform code, kept so in-place mutations by
     *  platform code can be copied back (see [Vm.toReal]). Null until the array first crosses the bridge. */
    var realMirror: Any? = null

    /** A shallow copy, for `array.clone()` (Java arrays are `Cloneable`; this one isn't a real array). */
    fun shallowCopy(): VmArray = VmArray(elementDescriptor, data.copyOf())

    companion object {
        /** An array of [length] elements of [elementDescriptor], each set to its type default. */
        fun of(elementDescriptor: String, length: Int): VmArray =
            VmArray(elementDescriptor, Array(length) { Descriptors.defaultValue(elementDescriptor) })
    }
}

/**
 * A functional-interface instance produced by an `invokedynamic` linked through `LambdaMetafactory`. It
 * records the functional interface ([interfaceType]), the single abstract method being implemented ([samName]
 * with erased descriptor [samDescriptor]), the implementation method ([impl]), and the arguments captured at
 * the creation site ([captured]). Invoking the abstract method runs [impl] with the captured arguments
 * followed by the call arguments.
 *
 * When interpreted code invokes the abstract method, the interpreter calls [invokeSam] directly. When the
 * value is passed to platform code, the [NativeBridge] wraps it in a real proxy of [interfaceType] whose calls
 * route back to [invokeSam].
 */
internal class VmLambda(
    val interfaceType: String,
    val samName: String,
    val samDescriptor: String,
    val impl: MethodHandleRef,
    val captured: List<Any?>,
    private val invoker: SamInvoker,
) {
    /** Run the implementation for a call to the abstract method with [samArgs] (interpreter value conventions). */
    fun invokeSam(samArgs: List<Any?>): Any? = invoker.invoke(this, samArgs)

    override fun toString(): String = "VmLambda($interfaceType.$samName)"
}

/** Runs a [VmLambda]'s implementation method. Supplied by the interpreter when a lambda is created. */
internal fun interface SamInvoker {
    fun invoke(lambda: VmLambda, samArgs: List<Any?>): Any?
}

/**
 * A placeholder for a bridged (platform) object between its `new` instruction and its constructor. The JVM's
 * `new` / `dup` / `<init>` sequence needs a reference on the stack before the object is constructed, but a
 * platform object cannot be allocated separately from its constructor. So `new` of a bridged class pushes this
 * holder, `<init>` runs the real constructor and stores the result in [real], and every copy the `dup` made
 * observes that result through the shared holder. Reference-use sites resolve the holder to [real].
 */
internal class Uninitialized(val type: String) {
    var real: Any? = null
}

/**
 * A reference to the method a [VmLambda] implementation resolves to, mirroring a class-file method handle.
 * [tag] is one of the `org.objectweb.asm.Opcodes.H_*` kinds and selects how the target is invoked.
 */
internal class MethodHandleRef(
    val tag: Int,
    val owner: String,
    val name: String,
    val descriptor: String,
    val isInterface: Boolean,
)

/**
 * Carries a thrown value up through interpreted frames until a matching handler is found. The value is a
 * [VmObject] exception, or a real [Throwable] surfaced by the bridge or by an intrinsic such as integer
 * division by zero.
 */
internal class VmException(val value: Any?) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this // interpreted control flow, not a host stack trace
}

/** Signals a class-file construct the interpreter does not implement. */
class VmUnsupportedException(message: String) : RuntimeException(message)

/**
 * Thrown from the interpreter's instruction loop when a run is cancelled (see [Vm.requestCancel]). It is not a
 * [VmException], so it is never routed to an interpreted `try/catch` handler — a cancelled run unwinds all the
 * way out, even through an interpreted `catch (Throwable)`.
 */
class VmInterruptedException : RuntimeException("run cancelled")
