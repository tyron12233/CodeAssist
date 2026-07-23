package dev.ide.jvm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

/**
 * A reflection-like view of one interpreted method (or constructor), for a host embedding the [Vm] as a
 * library executor over classes that exist only as class bytes. Values at this boundary use REAL conventions:
 * booleans are [Boolean], chars are [Char] (converted to and from the interpreter's computational form by the
 * declared parameter/return descriptors), and an interpreted object crossing out is its real peer.
 */
class VmMethodView internal constructor(
    private val vm: Vm,
    private val declaring: VmClass,
    private val node: MethodNode,
) {
    val name: String get() = node.name
    val isStatic: Boolean = node.access and Opcodes.ACC_STATIC != 0
    val isConstructor: Boolean = node.name == "<init>"
    val isVarargs: Boolean = node.access and Opcodes.ACC_VARARGS != 0
    val isAbstract: Boolean = node.access and Opcodes.ACC_ABSTRACT != 0
    val paramDescriptors: List<String> = Descriptors.paramTypes(node.desc)
    val returnDescriptor: String = Descriptors.returnType(node.desc)

    /** Invoke with real-convention [args] ([receiver] null for a static method or constructor; a peer receiver
     *  resolves to its interpreted instance). A constructor view allocates and returns the new instance as
     *  platform code sees it (its peer). */
    fun invoke(receiver: Any?, args: List<Any?>): Any? = vm.invokeView(declaring, node, receiver, args, paramDescriptors, returnDescriptor)

    override fun toString(): String = "${declaring.name}.${node.name}${node.desc}"
}

/** Whether [fqn] names a class this VM will interpret (its bytes are available and the policy claims it). */
fun Vm.hasInterpretedClass(fqn: String): Boolean = resolve(internalOf(fqn)) != null

/** Whether [value] is a peer standing for an interpreted instance of this or any VM. */
fun isVmPeer(value: Any?): Boolean = value is VmPeer

/** The binary class name of the interpreted instance [value] (a peer) stands for, or null. */
fun Vm.interpretedClassName(value: Any?): String? = interpretedClassNameOf(value)

/** [interpretedClassName] without a [Vm] at hand; the name rides on the peer itself. */
fun interpretedClassNameOf(value: Any?): String? =
    ((value as? VmPeer)?.vmObject() as? VmObject)?.vmClass?.name?.replace('/', '.')

/** All methods of [fqn]'s interpreted hierarchy (statics, instance methods, and Kotlin synthetics such as
 *  `name$default`), most-derived first, plus interpreted-interface default methods. Empty when the class is
 *  not interpreted. */
fun Vm.interpretedMethods(fqn: String): List<VmMethodView> {
    val start = resolve(internalOf(fqn)) ?: return emptyList()
    val out = ArrayList<VmMethodView>()
    val seenIfaces = HashSet<String>()
    val ifaceQueue = ArrayDeque<String>()
    var c: VmClass? = start
    while (c != null) {
        val cur = c
        cur.methods.forEach { if (it.name != "<init>" && it.name != "<clinit>") out.add(VmMethodView(this, cur, it)) }
        ifaceQueue.addAll(cur.interfaces)
        c = cur.superName?.let { resolve(it) }
    }
    while (ifaceQueue.isNotEmpty()) {
        val n = ifaceQueue.removeFirst()
        if (!seenIfaces.add(n)) continue
        val ic = resolve(n) ?: continue
        ic.methods.forEach {
            if (it.name != "<clinit>" && it.access and Opcodes.ACC_ABSTRACT == 0) out.add(VmMethodView(this, ic, it))
        }
        ifaceQueue.addAll(ic.interfaces)
    }
    return out
}

/** Whether [value] (a peer) stands for an interpreted instance assignable to interpreted type [fqn]. */
fun Vm.isInterpretedInstanceOf(value: Any?, fqn: String): Boolean {
    val obj = (value as? VmPeer)?.vmObject() as? VmObject ?: return false
    return isSubtype(obj.vmClass, internalOf(fqn))
}

/** The interpreted methods of the class [value] (a peer) stands for; empty when [value] is not a peer. */
fun Vm.interpretedMethodsOf(value: Any): List<VmMethodView> =
    interpretedClassName(value)?.let { interpretedMethods(it) } ?: emptyList()

/** The declared constructors of interpreted class [fqn] (including the Kotlin default-arguments synthetic). */
fun Vm.interpretedConstructors(fqn: String): List<VmMethodView> {
    val cls = resolve(internalOf(fqn)) ?: return emptyList()
    return cls.methods.filter { it.name == "<init>" }.map { VmMethodView(this, cls, it) }
}

/** The declared static field names of interpreted class [fqn] paired with their type descriptors. */
fun Vm.interpretedStaticFields(fqn: String): Map<String, String> =
    resolve(internalOf(fqn))?.staticFieldDescs ?: emptyMap()

/** Read a static field of interpreted class [fqn] (running its initializer first), in real conventions. */
fun Vm.interpretedStaticValue(fqn: String, field: String): Any? {
    val cls = resolve(internalOf(fqn)) ?: throw VmUnsupportedException("$fqn is not an interpreted class")
    ensureInitialized(cls)
    val desc = cls.staticFieldDescs[field] ?: throw VmUnsupportedException("no static field $field on $fqn")
    return Marshalling.vmToReal(toReal(cls.statics[field]), desc)
}

private fun internalOf(fqn: String): String = fqn.replace('.', '/')
