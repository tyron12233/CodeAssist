package dev.ide.jvm

/**
 * Implemented by every generated peer. A peer is the real object platform code holds for an interpreted
 * instance whose class extends a real supertype or implements a real interface; [vmObject] returns the
 * interpreted instance it stands for, so a peer handed back into interpreted code is resolved to that instance.
 */
interface VmPeer {
    fun vmObject(): Any
}

/**
 * Runs an interpreted override when a generated peer method is invoked by platform code. [peer] is the
 * generated peer instance whose method was called, [vmObject] is the interpreted instance it stands for,
 * [name] and [descriptor] identify the method, and [args] are the call arguments as real (boxed) values. The
 * result is returned as a real value for the peer method to hand back.
 *
 * [peer] is passed so the runtime can link it to [vmObject] on the first dispatch: a real superclass
 * constructor may call an overridden method while the peer is still being constructed (e.g. `ImageView(...)`
 * calls `setImageDrawable`), and the interpreted override may pass its instance back to platform code — which
 * needs the peer that is mid-construction, before the constructor returns and the runtime records it.
 */
fun interface PeerDispatch {
    fun invoke(peer: Any, vmObject: Any, name: String, descriptor: String, args: Array<Any?>): Any?
}

/** A method a peer overrides: it forwards to the interpreter, and a `super$name` trampoline invokes the real
 *  implementation declared by [ownerInternalName] (an interface when [ownerIsInterface]). */
class PeerMethod(
    val name: String,
    val descriptor: String,
    val ownerInternalName: String,
    val ownerIsInterface: Boolean,
)

/** What a peer for an interpreted class realizes: the real [superClass] to extend, the real [interfaces] to
 *  implement, the [methods] the interpreted class overrides, and the [abstractStubs] it leaves unimplemented
 *  (stubbed so the peer is concrete and reports a clear error if one is called). */
class PeerSpec(
    val superClass: Class<*>,
    val interfaces: List<Class<*>>,
    val methods: List<PeerMethod>,
    val abstractStubs: List<PeerMethod>,
    /** The interpreted class's internal name, used to give the generated peer a traceable name. */
    val className: String,
) {
    /** True when the interpreted class adds no real supertype behavior, so it needs no peer. */
    val isTrivial: Boolean
        get() = superClass == Any::class.java && interfaces.isEmpty() && methods.isEmpty() && abstractStubs.isEmpty()
}

/**
 * Produces a real object platform code can hold and invoke for an interpreted instance, forwarding the
 * overridden methods of [PeerSpec] into [PeerDispatch]. [superConstructorDescriptor] and [superConstructorArgs]
 * are the real superclass constructor the peer must invoke (the one the interpreted constructor called), so a
 * superclass without a no-argument constructor is initialized correctly. The JVM implementation
 * ([AsmPeerFactory]) generates a subclass with ASM; an Android implementation generates dex for the same
 * contract.
 */
interface PeerFactory {
    fun createPeer(
        vmObject: Any,
        spec: PeerSpec,
        dispatch: PeerDispatch,
        superConstructorDescriptor: String,
        superConstructorArgs: List<Any?>,
    ): Any

    /** The generated peer [Class] for [spec] without instantiating it — the stand-in for an interpreted
     *  type's class literal (instances crossing the bridge are peers of this class). */
    fun peerClass(spec: PeerSpec, superConstructorDescriptor: String): Class<*>

    /**
     * A real [Class] object that STANDS FOR an interpreted type in reflection: it is what
     * `loadClass`/`Class.forName`/a class literal of [className] resolve to, and it declares a public
     * constructor for each descriptor in [constructorDescriptors] (parameter types already realized to loadable
     * classes) so `Class.getConstructor(...)` finds a match. It is never instantiated directly — the runtime
     * intercepts `Constructor.newInstance` and routes it to the interpreter — so the stub constructor bodies are
     * inert. Cached per [className]; recorded so [interpretedNameOf] can map it back.
     */
    fun reflectionClass(className: String, constructorDescriptors: List<String>): Class<*>

    /** The interpreted internal name a [reflectionClass] result stands for, or null if [cls] is not one. */
    fun interpretedNameOf(cls: Class<*>): String?
}
