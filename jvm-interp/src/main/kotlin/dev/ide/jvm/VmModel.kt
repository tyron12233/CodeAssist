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

    /** Names of the declared VOLATILE (`ACC_VOLATILE`) instance / static fields. Their slot holds an
     *  [java.util.concurrent.atomic.AtomicReference] so reads/writes have real volatile (acquire/release)
     *  semantics across threads, instead of a plain map value with relaxed visibility. */
    val volatileInstanceFields: Set<String> =
        fields.filter { it.access and Opcodes.ACC_STATIC == 0 && it.access and Opcodes.ACC_VOLATILE != 0 }.mapTo(HashSet()) { it.name }
    val volatileStaticFields: Set<String> =
        fields.filter { it.access and Opcodes.ACC_STATIC != 0 && it.access and Opcodes.ACC_VOLATILE != 0 }.mapTo(HashSet()) { it.name }

    /** Static field values: defaulted from the descriptor, then seeded from any `ConstantValue` attribute (a
     *  `static final` primitive/String constant), and finally set by the class initializer. The JVM assigns a
     *  `ConstantValue` field during class preparation — before `<clinit>`, which carries no assignment for it —
     *  so honoring it here is required: a class compiled against a NON-final field (emitting a `getstatic`)
     *  reading a provider that declares it `final` (e.g. a library reading a regenerated `R` constant) would
     *  otherwise see the descriptor default instead of the constant. ASM exposes the attribute as
     *  [FieldNode.value] (an Integer for int/short/byte/char/boolean, else Long/Float/Double/String), which
     *  already matches the interpreter's value conventions. */
    val statics: HashMap<String, Any?> = HashMap<String, Any?>().apply {
        fields.filter { it.access and Opcodes.ACC_STATIC != 0 }.forEach {
            val initial = it.value ?: Descriptors.defaultValue(it.desc)
            // A volatile static's slot is an AtomicReference holder (see volatileStaticFields); putstatic in the
            // class initializer writes THROUGH it, so the holder identity stays stable.
            put(it.name, if (it.access and Opcodes.ACC_VOLATILE != 0) java.util.concurrent.atomic.AtomicReference(initial) else initial)
        }
    }

    /** Class-initialization state (JLS 12.4.2), so `<clinit>` runs exactly once even under concurrent first use.
     *  [initState] is one of [INIT_NONE]/[INIT_INPROGRESS]/[INIT_DONE]/[INIT_FAILED]; it is read without locking
     *  on the fast path and transitioned under [initLock]. [initThread] is the thread currently running the
     *  initializer, so its own recursive initialization proceeds while other threads block. */
    @Volatile @JvmField var initState: Int = INIT_NONE
    @JvmField var initThread: Thread? = null
    @JvmField val initLock = Any()

    /** The intrinsic monitor for `synchronized`/`wait`/`notify` on the class object of this type (a static
     *  `synchronized` method, or `synchronized(T.class)`). Created lazily by [Vm.monitorFor]. */
    @Volatile @JvmField var monitor: VmMonitor? = null

    /** The method declared on this class matching [name] and [descriptor], or null if it declares no such method. */
    fun declaredMethod(name: String, descriptor: String): MethodNode? =
        methods.firstOrNull { it.name == name && it.desc == descriptor }
}

// Class-initialization states for [VmClass.initState].
internal const val INIT_NONE = 0
internal const val INIT_INPROGRESS = 1
internal const val INIT_DONE = 2
internal const val INIT_FAILED = 3

/**
 * An instance of an interpreted [VmClass]. Field values are held in [fields], defaulted across the whole
 * instance-field chain when the object is allocated. A [VmObject] is not an instance of the real [vmClass]
 * (that class is never loaded by the host), so it cannot be passed to a platform method that expects that
 * type; passing an interpreted object to platform code requires a real peer object, which the bridge rejects
 * until that path is implemented.
 */
internal class VmObject(val vmClass: VmClass) {
    /** Instance-field values, keyed by name. The full key set is populated when the object is allocated (every
     *  declared instance field across the interpreted chain is defaulted in [Vm.newInstance]) and never grows
     *  afterward — a `putfield` only replaces an existing key's value. That fixed-key-set invariant makes
     *  concurrent value replacement structurally safe on a plain [HashMap] (no resize, no re-linking) across the
     *  real threads a multi-threaded program runs on; per-field visibility is relaxed, matching the VM's
     *  best-effort (not hardened-JMM) stance — shared state that needs ordering uses the real
     *  `java.util.concurrent` primitives, which are bridged. */
    val fields: HashMap<String, Any?> = HashMap()

    /** The real peer object platform code holds for this instance, created on demand when the object crosses to
     *  platform code or an interpreted `super` call needs the real supertype. Null until then. Volatile +
     *  created under `synchronized(this)` ([Vm.peerOf]/[Vm.initPeer]) so concurrent threads publish one peer. */
    @Volatile var peer: Any? = null

    /** The intrinsic monitor for `synchronized`/`wait`/`notify` on this instance, created lazily by
     *  [Vm.monitorFor]. */
    @Volatile @JvmField var monitor: VmMonitor? = null

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

    /** The intrinsic monitor for `synchronized`/`wait`/`notify` on this array, created lazily by
     *  [Vm.monitorFor]. */
    @Volatile @JvmField var monitor: VmMonitor? = null

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
