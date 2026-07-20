package dev.ide.jvm

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * The boundary between interpreted code and classes the interpreter does not run (the platform and standard
 * library, and any class the [Vm]'s policy excludes). Every call, field access, or construction the interpreter
 * cannot resolve to a [VmClass] is routed here, so a host can mediate all access to the outside world at one
 * point.
 *
 * Values crossing this boundary use the interpreter's conventions (see [Descriptors]). A [VmObject] cannot
 * cross yet and is rejected; a [VmLambda] is wrapped in a real proxy of its functional interface.
 */
interface NativeBridge {
    fun invokeStatic(owner: String, name: String, descriptor: String, args: List<Any?>): Any?
    fun invokeVirtual(receiver: Any, name: String, descriptor: String, args: List<Any?>): Any?
    fun getStatic(owner: String, name: String, descriptor: String): Any?
    fun putStatic(owner: String, name: String, descriptor: String, value: Any?)
    fun getField(receiver: Any, name: String, descriptor: String): Any?
    fun putField(receiver: Any, name: String, descriptor: String, value: Any?)
    fun construct(owner: String, descriptor: String, args: List<Any?>): Any?
}

/**
 * A [NativeBridge] that forwards to the real JVM by reflection. It marshals arguments and results between the
 * interpreter's representation and reflection: an `int` parameter is a Kotlin [Int] the reflection layer
 * autoboxes; a `boolean` parameter is materialized from `0`/`1`; a returned `char` comes back as an [Int]. A
 * [VmLambda] argument is wrapped in a [Proxy] of the target functional interface whose calls re-enter the
 * interpreter. A [VmObject] argument has no real counterpart and is rejected.
 */
class ReflectiveBridge(
    private val loader: ClassLoader = ReflectiveBridge::class.java.classLoader,
    /** When set, an exception thrown while running an interpreted lambda invoked by platform code through a
     *  [Proxy] is reported here and the proxied method returns a zero value, instead of propagating into the
     *  platform caller. A preview host sets this so a buggy async callback (e.g. a `Runnable` a view posts to a
     *  Looper, running outside the render's error boundary) degrades instead of crashing the process; a console
     *  run leaves it null so failures propagate. */
    private val proxyExceptionSink: ((Throwable) -> Unit)? = null,
) : NativeBridge {

    // Reflection resolution is deterministic given the class + name + descriptor and is repeated on every
    // bridged call (a composable makes many per recomposition), so cache each lookup. Real classes are
    // immutable, so the caches never need invalidation.
    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val paramClassCache = ConcurrentHashMap<String, Array<Class<*>>>()
    private val paramDescCache = ConcurrentHashMap<String, List<String>>()
    private val methodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, MethodRef>>()
    private val ctorCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, CtorRef>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Field>>()
    private class MethodRef(val m: Method?)
    private class CtorRef(val c: Constructor<*>?)

    private fun loadClass(internal: String): Class<*> =
        classCache.getOrPut(internal) { Class.forName(internal.replace('/', '.'), false, loader) }

    /** Parameter [Class]es for a method descriptor, cached (the parse and class loads otherwise repeat per call). */
    private fun paramClasses(descriptor: String): Array<Class<*>> =
        paramClassCache.getOrPut(descriptor) { Descriptors.paramTypes(descriptor).map { classFor(it) }.toTypedArray() }

    private fun paramDescs(descriptor: String): List<String> =
        paramDescCache.getOrPut(descriptor) { Descriptors.paramTypes(descriptor) }

    /** The method for (class, name+descriptor), resolved once and made accessible; null cached too. */
    private fun resolveMethod(cls: Class<*>, name: String, descriptor: String): Method? =
        methodCache.getOrPut(cls) { ConcurrentHashMap() }.getOrPut(name + descriptor) {
            MethodRef(findMethod(cls, name, paramClasses(descriptor))?.also { runCatching { it.isAccessible = true } })
        }.m

    /** The constructor for (class, descriptor), resolved once and made accessible; null cached too. */
    private fun resolveConstructor(cls: Class<*>, descriptor: String): Constructor<*>? =
        ctorCache.getOrPut(cls) { ConcurrentHashMap() }.getOrPut(descriptor) {
            val pt = paramClasses(descriptor)
            CtorRef(cls.declaredConstructors.firstOrNull { paramsMatch(it.parameterTypes, pt) }?.also { runCatching { it.isAccessible = true } })
        }.c

    /** The field [name] on [cls] or a supertype, resolved once. */
    private fun resolveField(cls: Class<*>, name: String): Field =
        fieldCache.getOrPut(cls) { ConcurrentHashMap() }.getOrPut(name) { findField(cls, name) }

    /** The [Class] a single type descriptor names: primitive classes for primitives, loaded classes for refs. */
    private fun classFor(descriptor: String): Class<*> = when (descriptor[0]) {
        'I' -> Int::class.javaPrimitiveType!!
        'J' -> Long::class.javaPrimitiveType!!
        'F' -> Float::class.javaPrimitiveType!!
        'D' -> Double::class.javaPrimitiveType!!
        'Z' -> Boolean::class.javaPrimitiveType!!
        'B' -> Byte::class.javaPrimitiveType!!
        'C' -> Char::class.javaPrimitiveType!!
        'S' -> Short::class.javaPrimitiveType!!
        'L' -> loadClass(descriptor.substring(1, descriptor.length - 1))
        '[' -> java.lang.reflect.Array.newInstance(classFor(descriptor.substring(1)), 0).javaClass
        else -> error("bad type descriptor: $descriptor")
    }

    /** Convert an interpreter value into the form reflection expects for a parameter of [descriptor]. A lambda
     *  passed through an Object-typed parameter (stored in a container, an AtomicReference) is proxied as its
     *  OWN functional interface from the lambda's call site, since Object names no interface to implement. */
    private fun marshalIn(value: Any?, descriptor: String): Any? = when {
        value is VmLambda -> proxyFor(value, if (descriptor == "Ljava/lang/Object;") "L${value.interfaceType};" else descriptor)
        descriptor == "Z" -> (value as Int) != 0
        descriptor == "B" -> (value as Int).toByte()
        descriptor == "C" -> (value as Int).toChar()
        descriptor == "S" -> (value as Int).toShort()
        else -> value // int/long/float/double pass as-is; references pass through
    }

    /** Convert a reflection result back to the interpreter's representation by the STATIC result type: a
     *  primitive boolean/byte/char/short takes the computational-int form; a reference result passes through
     *  as the real object (a peer handed back is resolved to the interpreted instance it stands for). */
    private fun marshalOut(value: Any?, resultDescriptor: String): Any? = Marshalling.realToVm(value, resultDescriptor)

    private fun marshalArgs(descriptor: String, args: List<Any?>): Array<Any?> =
        paramDescs(descriptor).mapIndexed { i, d -> marshalIn(args[i], d) }.toTypedArray()

    /** Interpreted objects and arrays are converted to their real forms before reaching the bridge; this guards
     *  the invariant so a missed conversion fails clearly instead of reaching reflection as an interpreter type. */
    private fun rejectVmObjects(args: List<Any?>) {
        require(args.none { it is VmObject || it is VmArray }) {
            "an interpreted value reached the bridge without conversion"
        }
    }

    override fun invokeStatic(owner: String, name: String, descriptor: String, args: List<Any?>): Any? {
        rejectVmObjects(args)
        val m = resolveMethod(loadClass(owner), name, descriptor) ?: throw VmUnsupportedException("no static $owner.$name$descriptor")
        return marshalOut(invoked { m.invoke(null, *marshalArgs(descriptor, args)) }, Descriptors.returnType(descriptor))
    }

    override fun invokeVirtual(receiver: Any, name: String, descriptor: String, args: List<Any?>): Any? {
        if (receiver is VmObject) throw VmUnsupportedException("virtual call `$name` on an interpreted object requires a real peer")
        rejectVmObjects(args)
        val m = resolveMethod(receiver.javaClass, name, descriptor)
            ?: throw VmUnsupportedException("no method $name$descriptor on ${receiver.javaClass.name}")
        return marshalOut(invoked { m.invoke(receiver, *marshalArgs(descriptor, args)) }, Descriptors.returnType(descriptor))
    }

    override fun construct(owner: String, descriptor: String, args: List<Any?>): Any {
        rejectVmObjects(args)
        val ctor = resolveConstructor(loadClass(owner), descriptor)
            ?: throw VmUnsupportedException("no constructor $owner$descriptor")
        return invoked { ctor.newInstance(*marshalArgs(descriptor, args)) }!!
    }

    /** Run a reflective invocation, converting the exception a real method threw into a [VmException] so an
     *  interpreted `try/catch` can match it by its real type. A [VmException] surfacing from nested interpreted
     *  code (an interpreted lambda invoked by the platform) is rethrown as-is rather than wrapped again. */
    private fun invoked(action: () -> Any?): Any? =
        try {
            action()
        } catch (e: InvocationTargetException) {
            when (val target = e.targetException) {
                is VmException -> throw target
                null -> throw e
                else -> throw VmException(target)
            }
        }

    override fun getStatic(owner: String, name: String, descriptor: String): Any? =
        marshalOut(resolveField(loadClass(owner), name).get(null), descriptor)

    override fun putStatic(owner: String, name: String, descriptor: String, value: Any?) {
        resolveField(loadClass(owner), name).set(null, marshalIn(value, descriptor))
    }

    override fun getField(receiver: Any, name: String, descriptor: String): Any? =
        marshalOut(resolveField(receiver.javaClass, name).get(receiver), descriptor)

    override fun putField(receiver: Any, name: String, descriptor: String, value: Any?) {
        resolveField(receiver.javaClass, name).set(receiver, marshalIn(value, descriptor))
    }

    /** A field [name] declared on [cls] or a supertype, made accessible (covers protected/private fields a
     *  subclass reaches, which `getField` would not return). */
    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field {
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { runCatching { it.isAccessible = true }; return it }
            c = c.superclass
        }
        throw VmUnsupportedException("no field $name on ${cls.name}")
    }

    /**
     * Wrap [lambda] in a real proxy of the functional interface named by [descriptor], so platform code that
     * expects that interface can call it. Each abstract-method call marshals its arguments into the
     * interpreter's representation, runs the lambda, and marshals the result back to the method's return type.
     */
    private fun proxyFor(lambda: VmLambda, descriptor: String): Any {
        require(descriptor.startsWith("L")) { "a lambda argument must target an interface type, got $descriptor" }
        val iface = loadClass(descriptor.substring(1, descriptor.length - 1))
        require(iface.isInterface) { "${iface.name} is not a functional interface" }
        return Proxy.newProxyInstance(loader, arrayOf(iface)) { proxy, method, callArgs ->
            when (method.name) {
                "toString" -> lambda.toString()
                "hashCode" -> System.identityHashCode(lambda)
                "equals" -> callArgs?.getOrNull(0) === proxy
                else -> {
                    val paramTypes = method.parameterTypes
                    val vmArgs = (callArgs ?: emptyArray()).mapIndexed { i, a -> realArgToVm(a, paramTypes[i]) }
                    val sink = proxyExceptionSink
                    if (sink == null) marshalReturn(lambda.invokeSam(vmArgs), method.returnType)
                    else try {
                        marshalReturn(lambda.invokeSam(vmArgs), method.returnType)
                    } catch (t: Throwable) {
                        sink(t)
                        zeroReturn(method.returnType)
                    }
                }
            }
        }
    }

    /** A type-correct zero for [returnType], returned when a guarded proxy call fails (see [proxyExceptionSink]). */
    private fun zeroReturn(returnType: Class<*>): Any? = when (returnType) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> false
        Char::class.javaPrimitiveType -> ' '
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    /** Convert a real argument passed from platform code by its declared parameter type: a primitive position
     *  takes the computational form, a reference position only unwraps a peer. */
    private fun realArgToVm(value: Any?, type: Class<*>): Any? =
        if (type.isPrimitive) Marshalling.realPrimToVm(value) else Marshalling.realToVm(value)

    /** Convert an interpreter value back to what a proxy method of [returnType] must return. */
    private fun marshalReturn(value: Any?, returnType: Class<*>): Any? = when (returnType) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> (value as Int) != 0
        Char::class.javaPrimitiveType -> (value as Int).toChar()
        Byte::class.javaPrimitiveType -> (value as Int).toByte()
        Short::class.javaPrimitiveType -> (value as Int).toShort()
        else -> value
    }

    /**
     * A method [name] with matching [paramTypes], preferring one declared on a public class or interface so it
     * can be invoked without [Method.setAccessible]. A concrete platform class is often not exported by its
     * module (for example `java.util.stream.IntPipeline`), so the public interface method it overrides
     * (`IntStream.map`) is used instead. Falls back to any matching method up the hierarchy.
     */
    private fun findMethod(cls: Class<*>, name: String, paramTypes: Array<Class<*>>): Method? {
        publicMethod(cls, name, paramTypes)?.let { return it }
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && paramsMatch(it.parameterTypes, paramTypes) }?.let { return it }
            c = c.superclass
        }
        return cls.methods.firstOrNull { it.name == name && paramsMatch(it.parameterTypes, paramTypes) }
    }

    /** A matching method declared on a public type in [cls]'s hierarchy (superclasses and interfaces), or null. */
    private fun publicMethod(cls: Class<*>, name: String, paramTypes: Array<Class<*>>): Method? {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(cls)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (Modifier.isPublic(c.modifiers)) {
                c.declaredMethods.firstOrNull {
                    it.name == name && Modifier.isPublic(it.modifiers) && paramsMatch(it.parameterTypes, paramTypes)
                }?.let { return it }
            }
            c.superclass?.let { queue.add(it) }
            c.interfaces.forEach { queue.add(it) }
        }
        return null
    }

    private fun paramsMatch(actual: Array<Class<*>>, expected: Array<Class<*>>): Boolean =
        actual.size == expected.size && actual.indices.all { actual[it] == expected[it] }
}
