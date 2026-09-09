package dev.ide.jvm

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
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
 * [VmLambda] argument is wrapped in a [Proxy] of its own functional interface (see [realLambda]) whose calls
 * re-enter the interpreter. A [VmObject] argument has no real counterpart and is rejected.
 */
class ReflectiveBridge(
    private val loader: ClassLoader = ReflectiveBridge::class.java.classLoader,
    /** When set, an exception thrown while running an interpreted lambda invoked by platform code through a
     *  [Proxy] is reported here and the proxied method returns a zero value, instead of propagating into the
     *  platform caller. A preview host sets this so a buggy async callback (e.g. a `Runnable` a view posts to a
     *  Looper, running outside the render's error boundary) degrades instead of crashing the process; a console
     *  run leaves it null so failures propagate. */
    private val proxyExceptionSink: ((Throwable) -> Unit)? = null,
    /** When set, supplies the value a guarded proxy method returns after a failure (given the method, the real
     *  arguments, and the error) — for a caller that cannot tolerate the type's zero value. A Compose measure
     *  lambda whose zero (null `MeasureResult`) would NPE the layout pass hands back an empty result instead.
     *  Return null to fall back to [zeroReturn]. Consulted after [proxyExceptionSink]. */
    private val proxyFallback: ((java.lang.reflect.Method, Array<Any?>, Throwable) -> Any?)? = null,
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

    /** Whether [internal] is loadable on the host at all — false for a project-only type (e.g. a newer Compose
     *  class the bundled runtime lacks) that is interpreted, never bridged. */
    private fun classLoadable(internal: String): Boolean =
        classCache.containsKey(internal) || runCatching { loadClass(internal) }.isSuccess

    /** Parameter [Class]es for a method descriptor, cached (the parse and class loads otherwise repeat per call). */
    private fun paramClasses(descriptor: String): Array<Class<*>> =
        paramClassCache.getOrPut(descriptor) { Descriptors.paramTypes(descriptor).map { classFor(it) }.toTypedArray() }

    private fun paramDescs(descriptor: String): List<String> =
        paramDescCache.getOrPut(descriptor) { Descriptors.paramTypes(descriptor) }

    /** The method for (class, name+descriptor), resolved once and made accessible; null cached too. The RETURN
     *  type disambiguates overloads with identical params: a class can declare two methods with the same name
     *  and parameter types but different return types (Kotlin does — e.g. `RememberSaveableKt.rememberSaveable`
     *  has `(…Function0;Composer;I)Object` AND `(…Function0;Composer;I)MutableState`), which the invoked
     *  bytecode distinguishes by the full descriptor. Matching params-only would pick between them by
     *  `Class.getDeclaredMethods()` order (unspecified, varies per JVM/ART run) — and invoking the wrong overload
     *  (whose body casts its result, e.g. to `MutableState`) throws a `ClassCastException`. Falls back to a
     *  params-only match when no return-type match exists (a covariant/bridge return the descriptor names more
     *  precisely than the reflected method reports). */
    private fun resolveMethod(cls: Class<*>, name: String, descriptor: String): Method? =
        methodCache.getOrPut(cls) { ConcurrentHashMap() }.getOrPut(name + descriptor) {
            val params = paramClasses(descriptor)
            val ret = returnClass(descriptor)
            // Prefer a return-type match; retry params-only only when the return type was known AND matched nothing.
            val m = findMethod(cls, name, params, ret) ?: if (ret != null) findMethod(cls, name, params, null) else null
            MethodRef(m?.also { runCatching { it.isAccessible = true } })
        }.m

    /** The real [Class] the [descriptor]'s return type names (`Void.TYPE` for `V`), or null when it isn't
     *  loadable here — then return-type disambiguation is skipped and resolution falls back to params-only. */
    private fun returnClass(descriptor: String): Class<*>? = runCatching {
        val r = Descriptors.returnType(descriptor)
        if (r == "V") Void.TYPE else classFor(r)
    }.getOrNull()

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

    /** Convert an interpreter value into the form reflection expects for a parameter of [descriptor]. */
    private fun marshalIn(value: Any?, descriptor: String): Any? = when {
        value is VmLambda -> realLambda(value, descriptor)
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
        // A lambda receiver is a call to a method of its REAL interface other than the abstract one (a default
        // method; the interpreter answers the abstract method and Object's itself): run it on the lambda's proxy.
        val target = if (receiver is VmLambda) realLambda(receiver, "Ljava/lang/Object;") else receiver
        val m = resolveMethod(target.javaClass, name, descriptor)
            ?: throw VmUnsupportedException("no method $name$descriptor on ${target.javaClass.name}")
        return marshalOut(invoked { m.invoke(target, *marshalArgs(descriptor, args)) }, Descriptors.returnType(descriptor))
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
            when (val target = unwrapUndeclared(e.targetException)) {
                is VmException -> throw target
                null -> throw e
                else -> throw VmException(target)
            }
        }

    /**
     * What a real method actually threw, with the wrapper a [Proxy] adds stripped off. Platform code invokes an
     * interpreted lambda (or a proxy peer) through a [Proxy], whose generated method wraps any CHECKED throwable
     * the functional interface does not declare in an [UndeclaredThrowableException]. Interpreted Kotlin declares
     * nothing, so an `InterruptedException` from a `Thread.sleep` inside a `Runnable`/`forEach` lambda would come
     * back to the interpreted caller under a type no `catch` of it can match. The wrapper is an artifact of how
     * the VM hands interpreted code to the platform, so the interpreted caller sees the cause instead.
     */
    private fun unwrapUndeclared(t: Throwable?): Throwable? =
        if (t is UndeclaredThrowableException) t.undeclaredThrowable ?: t else t

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
     * The real object a [VmLambda] crosses as when platform code expects a value of [descriptor]: a [Proxy] of
     * the lambda's OWN functional interface whenever the host has that interface. The parameter type is often
     * only a SUPERTYPE of it (`Lifecycle.addObserver(LifecycleObserver)` receiving a
     * `LifecycleEventObserver { _, e -> }`), and a proxy of the parameter type alone carries none of the lambda's
     * methods: the registry finds no `LifecycleEventObserver` behind the marker interface and never dispatches
     * `onStateChanged`, silently. An `Object` parameter (a lambda stored in a container or an AtomicReference)
     * names no interface at all, so it too takes the lambda's own type.
     *
     * Only when the lambda's interface is host-absent (a project `fun interface`, or a newer Compose type the
     * bundled runtime lacks) is the parameter's interface proxied instead; and when that is absent as well the
     * interpreted lambda passes through opaquely: no host code can INVOKE a SAM it holds no reference to, it
     * can only store the value and hand it back to interpreted code. (A VmObject already passes through.)
     *
     * One proxy per (lambda, interface), so every crossing hands the platform the same object (see
     * [VmLambda.proxyAs]).
     */
    private fun realLambda(lambda: VmLambda, descriptor: String): Any {
        val own = lambda.interfaceType.takeIf(::classLoadable)?.let(::loadClass)
        val param = descriptor.takeIf { it.startsWith("L") && it != "Ljava/lang/Object;" }
            ?.let { it.substring(1, it.length - 1) }?.takeIf(::classLoadable)?.let(::loadClass)
        val iface = when {
            own != null && (param == null || param.isAssignableFrom(own)) -> own
            param != null -> param
            else -> return lambda
        }
        return lambda.proxyAs(iface) { proxyFor(lambda, iface) }
    }

    /**
     * Wrap [lambda] in a real proxy of the functional interface [iface], so platform code that expects that
     * interface can call it. Each abstract-method call marshals its arguments into the interpreter's
     * representation, runs the lambda, and marshals the result back to the method's return type. Callers go
     * through [realLambda], which caches the proxy on the lambda.
     */
    private fun proxyFor(lambda: VmLambda, iface: Class<*>): Any {
        require(iface.isInterface) { "${iface.name} is not a functional interface" }
        return Proxy.newProxyInstance(loader, arrayOf(iface), LambdaHandler(lambda))
    }

    /**
     * The handler behind a lambda's proxy. `Object`'s methods are answered by the lambda's identity: two
     * proxies of one lambda are equal, whichever interface each was made for. A DEFAULT method of the interface
     * (`Comparator.reversed`) runs its own body on the proxy, so its calls to the abstract method re-enter here
     * and reach the lambda; where the platform cannot run a default method on a proxy the call falls through to
     * the lambda body, as every call did before. Everything else is the abstract method itself.
     */
    private inner class LambdaHandler(val lambda: VmLambda) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, callArgs: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return when (method.name) {
                "toString" -> lambda.toString()
                "hashCode" -> System.identityHashCode(lambda)
                else -> callArgs?.getOrNull(0).let { it === proxy || lambdaBehind(it) === lambda } // equals
            }
            if (method.isDefault) invokeDefaultMethod?.let { return it(proxy, method, callArgs) }
            val paramTypes = method.parameterTypes
            val vmArgs = (callArgs ?: emptyArray()).mapIndexed { i, a -> realArgToVm(a, paramTypes[i]) }
            // invokeSamReal (not invokeSam): the result crosses to platform code here, so an interpreted object
            // return (e.g. a `DisposableEffectResult` from an inlined `onDispose { }`) is converted to its real
            // peer, else the raw VmObject reaches the caller and ClassCastExceptions there.
            val guarded = proxyExceptionSink != null || proxyFallback != null
            return if (!guarded) marshalReturn(lambda.invokeSamReal(vmArgs), method.returnType)
            else try {
                marshalReturn(lambda.invokeSamReal(vmArgs), method.returnType)
            } catch (t: Throwable) {
                proxyExceptionSink?.invoke(t)
                proxyFallback?.invoke(method, callArgs ?: emptyArray(), t) ?: zeroReturn(method.returnType)
            }
        }
    }

    /** The [VmLambda] a lambda proxy of this bridge stands for, or null for any other value. */
    private fun lambdaBehind(value: Any?): VmLambda? =
        if (value is Proxy) (Proxy.getInvocationHandler(value) as? LambdaHandler)?.lambda else null

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
     * A method [name] with matching [paramTypes], preferring one declared on a public class or interface whose
     * members are actually reachable, so it can be invoked without [Method.setAccessible]. A concrete platform
     * class is often not public (`java.util.stream.IntPipeline`) or not exported by its module
     * (`sun.java2d.SunGraphics2D`, the runtime class of every `Graphics2D` Swing hands a `paintComponent`), so
     * the public supertype method it overrides (`IntStream.map`, `java.awt.Graphics2D.setRenderingHint`) is
     * used instead: reflection resolves the declaring class, and invoking a supertype's method still
     * dispatches virtually to the real one. Falls back to any matching method up the hierarchy.
     */
    private fun findMethod(cls: Class<*>, name: String, paramTypes: Array<Class<*>>, returnType: Class<*>?): Method? {
        publicMethod(cls, name, paramTypes, returnType)?.let { return it }
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredMethods.firstOrNull { matches(it, name, paramTypes, returnType) }?.let { return it }
            c = c.superclass
        }
        return cls.methods.firstOrNull { matches(it, name, paramTypes, returnType) }
    }

    /** A matching method declared on a public, reachable type in [cls]'s hierarchy (superclasses and
     *  interfaces), or null. */
    private fun publicMethod(cls: Class<*>, name: String, paramTypes: Array<Class<*>>, returnType: Class<*>?): Method? {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(cls)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (Modifier.isPublic(c.modifiers)) {
                c.declaredMethods.firstOrNull {
                    Modifier.isPublic(it.modifiers) && matches(it, name, paramTypes, returnType) && openable(it)
                }?.let { return it }
            }
            c.superclass?.let { queue.add(it) }
            c.interfaces.forEach { queue.add(it) }
        }
        return null
    }

    /** Whether [m] can actually be invoked from here, decided by trying the access rather than by reading
     *  modifiers: a `public` method of a `public` class in a package its module does not export
     *  (`sun.java2d.SunGraphics2D.setRenderingHint`, `sun.nio.cs.UTF_8.newDecoder`) passes every modifier check
     *  but throws `InaccessibleObjectException` here and `IllegalAccessException` from `invoke`. Asking the
     *  runtime keeps this correct on ART too, which enforces no module boundaries and answers true throughout.
     *  Resolution is cached per (class, name+descriptor), so the probe runs once per call site. */
    private fun openable(m: Method): Boolean = runCatching { m.isAccessible = true }.isSuccess

    /** Name + parameter types must match; the [returnType] must match too when given (null = don't constrain it,
     *  the params-only fallback). This is what separates two overloads that differ ONLY by return type. */
    private fun matches(m: Method, name: String, paramTypes: Array<Class<*>>, returnType: Class<*>?): Boolean =
        m.name == name && paramsMatch(m.parameterTypes, paramTypes) && (returnType == null || m.returnType == returnType)

    private fun paramsMatch(actual: Array<Class<*>>, expected: Array<Class<*>>): Boolean =
        actual.size == expected.size && actual.indices.all { actual[it] == expected[it] }

    private companion object {
        /**
         * `InvocationHandler.invokeDefault(proxy, method, args)`, which runs an interface's default method on a
         * proxy (Java 16+; absent on older ART releases, resolved reflectively so a missing method is a null here
         * and not a `NoSuchMethodError` at the first proxied call). The default method's own exception surfaces
         * unwrapped, as it would from a direct call.
         */
        val invokeDefaultMethod: ((Any, Method, Array<Any?>?) -> Any?)? = runCatching {
            InvocationHandler::class.java.getMethod("invokeDefault", Any::class.java, Method::class.java, Array<Any>::class.java)
        }.getOrNull()?.let { m ->
            { proxy: Any, method: Method, args: Array<Any?>? ->
                try {
                    m.invoke(null, proxy, method, args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }
        }
    }
}
