package dev.ide.interp.compose

import dev.ide.interp.ClassProxyFactory
import dev.ide.jvm.AsmPeerFactory
import dev.ide.jvm.PeerDispatch
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.PeerMethod
import dev.ide.jvm.PeerSpec
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A [ClassProxyFactory] backed by jvm-interp's [PeerFactory]: realizes an interpreted `object : SomeClass() { }`
 * as a real generated SUBCLASS of the library class, whose overridden methods route into the interpreter. A
 * `java.lang.reflect.Proxy` (used for interfaces in interp-core) can't extend a class; jvm-interp already
 * generates concrete subclasses this way for the bytecode VM's peers, with a desktop ([AsmPeerFactory], ASM)
 * and an Android (dex) implementation of the same [PeerFactory] contract — so passing the host's factory keeps
 * the on-device path (dexing) working too.
 *
 * The peer overrides only the methods the interpreted object actually provides (by name); un-overridden abstract
 * methods are stubbed so the class is concrete, and every other method keeps the real superclass behaviour. The
 * real superclass is initialized through its no-argument constructor — an `object : SomeClass(arg)` whose only
 * constructor takes arguments isn't realized (the factory declines → the honest boundary stands).
 */
class PeerClassProxyFactory(private val peerFactory: PeerFactory = AsmPeerFactory()) : ClassProxyFactory {

    override fun proxyOrNull(
        interpretedObject: Any,
        superClass: Class<*>,
        interfaces: List<Class<*>>,
        overriddenMethods: Set<String>,
        invoker: (String, List<Any?>) -> Any?,
    ): Any? {
        if (superClass.isInterface || Modifier.isFinal(superClass.modifiers)) return null
        return runCatching {
            val overridable = overridableMethods(superClass, interfaces)
            val methods = overridable.filter { it.name in overriddenMethods }.map { it.toPeerMethod() }.dedupe()
            if (methods.isEmpty()) return null // nothing to route → no reason to generate a peer
            val stubs = overridable
                .filter { Modifier.isAbstract(it.modifiers) && it.name !in overriddenMethods }
                .map { it.toPeerMethod() }.dedupe()
            val spec = PeerSpec(superClass, interfaces, methods, stubs, superClass.name.replace('.', '/') + "\$interp")
            val dispatch = PeerDispatch { _, _, name, _, args -> invoker(name, (args ?: emptyArray()).toList()) }
            peerFactory.createPeer(interpretedObject, spec, dispatch, "()V", emptyList())
        }.getOrNull()
    }

    /** Non-final, non-static, non-private instance methods of [superClass]'s hierarchy plus the abstract methods
     *  of [interfaces] — the methods a subclass may override. Object methods are excluded. */
    private fun overridableMethods(superClass: Class<*>, interfaces: List<Class<*>>): List<Method> {
        val all = (superClass.methods.asSequence() + interfaces.asSequence().flatMap { it.methods.asSequence() })
        return all.filter {
            !Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) &&
                it.declaringClass != Any::class.java
        }.toList()
    }

    private fun Method.toPeerMethod() =
        PeerMethod(name, methodDescriptor(this), internalName(declaringClass), declaringClass.isInterface)

    private fun List<PeerMethod>.dedupe() = distinctBy { it.name + it.descriptor }

    private fun methodDescriptor(m: Method): String =
        "(" + m.parameterTypes.joinToString("") { descriptorOf(it) } + ")" + descriptorOf(m.returnType)

    private fun internalName(c: Class<*>): String = c.name.replace('.', '/')

    private fun descriptorOf(c: Class<*>): String = when {
        c == Void.TYPE -> "V"
        c == Boolean::class.javaPrimitiveType -> "Z"
        c == Byte::class.javaPrimitiveType -> "B"
        c == Char::class.javaPrimitiveType -> "C"
        c == Short::class.javaPrimitiveType -> "S"
        c == Int::class.javaPrimitiveType -> "I"
        c == Long::class.javaPrimitiveType -> "J"
        c == Float::class.javaPrimitiveType -> "F"
        c == Double::class.javaPrimitiveType -> "D"
        c.isArray -> "[" + descriptorOf(c.componentType)
        else -> "L" + internalName(c) + ";"
    }
}
