package dev.ide.jvm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodNode
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * `java.lang.invoke` for interpreted code.
 *
 * Two things the bridge cannot do by forwarding the call:
 *
 *  - `MethodHandles.lookup()` reads its CALLER off the stack. Forwarded through `Method.invoke`, the caller is
 *    the reflection machinery: Android refuses it outright (`illegal lookupClass: class
 *    java.lang.reflect.Method`), and a desktop JVM hands back a lookup for the wrong class. The interpreter
 *    knows which class is running, so the lookup it returns is that class's ([lookupFor]).
 *  - A member of an interpreted class does not exist on the real class standing for it, so a real lookup finds
 *    nothing there. [intercept] answers `findStatic`/`findVirtual`/`findConstructor`/`unreflect`/field getters
 *    and setters with a REAL [MethodHandle] bound to the interpreter, which the program can pass around, bind
 *    and adapt like any other.
 *
 * Such a handle's type has `Object` wherever the member's has an interpreted class: what crosses to real code
 * for an interpreted instance is its peer, which is not an instance of the stand-in class, so a faithful type
 * would reject every argument. `invoke` and `invokeExact` are run as `invokeWithArguments` for the same reason
 * (and because neither can be called reflectively at all).
 *
 * A variable handle over an interpreted field, or over an array's elements, is a [VmVarHandle].
 */
internal class InterpretedHandles(private val vm: Vm, private val reflection: InterpretedReflection) {

    /** The lookup `MethodHandles.lookup()` returns to code running in [caller]. Its `lookupClass()` is the class
     *  standing for [caller], which the VM names as the interpreted class. Null (no interpreted caller) gets the
     *  host's own. */
    fun lookupFor(caller: VmClass?): MethodHandles.Lookup {
        val standIn = caller?.let { vm.classForInterpreted(it.name) } ?: return HOST
        return HOST.`in`(standIn)
    }

    /** Handles a `java.lang.invoke` call interpreted code makes; [args] are real values. [NOT_HANDLED] leaves it
     *  to the bridge (a lookup on a real class, a handle operation that needs no help). */
    fun intercept(receiver: Any?, owner: String, name: String, descriptor: String, args: List<Any?>): Any? = when {
        receiver is MethodHandle && (name == "invoke" || name == "invokeExact") -> invoke(receiver, descriptor, args)
        receiver is MethodHandles.Lookup -> onLookup(name, args)
        // An array of references an interpreted program makes is the interpreter's own, which a real handle
        // cannot address; one handle type serves those and the real primitive arrays alike.
        receiver == null && owner == "java/lang/invoke/MethodHandles" && name == "arrayElementVarHandle" ->
            (args.getOrNull(0) as? Class<*>)?.takeIf { it.isArray }?.let { arrayVarHandle(it) } ?: NOT_HANDLED
        // AtomicIntegerFieldUpdater.newUpdater(Foo.class, "x") and its long and reference siblings.
        receiver == null && name == "newUpdater" -> fieldUpdater(owner, args)
        // privateLookupIn(Foo.class, lookup): there is no more private access to be had than the VM already gives.
        receiver == null && owner == "java/lang/invoke/MethodHandles" && name == "privateLookupIn" ->
            (args.getOrNull(0) as? Class<*>)?.takeIf { interpretedName(it) != null }?.let { HOST.`in`(it) } ?: NOT_HANDLED
        else -> NOT_HANDLED
    }

    private fun invoke(handle: MethodHandle, descriptor: String, args: List<Any?>): Any? {
        val params = Descriptors.paramTypes(descriptor)
        val real = args.mapIndexed { i, a -> Marshalling.toRealArg(a, params[i]) }
        val result = raising { handle.invokeWithArguments(real) }
        val ret = Descriptors.returnType(descriptor)
        return if (ret == "V") null else Marshalling.realToVm(result, ret)
    }

    private fun onLookup(name: String, args: List<Any?>): Any? {
        when (name) {
            "unreflect" -> {
                val method = args.getOrNull(0) as? Method ?: return NOT_HANDLED
                val (internal, node) = reflection.mirrored(method) ?: return NOT_HANDLED
                val cls = vm.resolve(internal) ?: return NOT_HANDLED
                return methodHandle(cls, node, virtual = node.access and Opcodes.ACC_STATIC == 0)
            }
            "unreflectVarHandle" -> {
                val field = args.getOrNull(0) as? java.lang.reflect.Field ?: return NOT_HANDLED
                val (internal, node) = reflection.mirrored(field) ?: return NOT_HANDLED
                val cls = vm.resolve(internal) ?: return NOT_HANDLED
                return fieldVarHandle(cls, node.name, static = node.access and Opcodes.ACC_STATIC != 0)
            }
            "unreflectConstructor" -> {
                val ctor = args.getOrNull(0) as? Constructor<*> ?: return NOT_HANDLED
                val cls = interpretedName(ctor.declaringClass)?.let(vm::resolve) ?: return NOT_HANDLED
                val wanted = Type.getConstructorDescriptor(ctor)
                val node = cls.methods.firstOrNull { it.name == "<init>" && vm.realizedCtorDescriptor(it) == wanted }
                    ?: return NOT_HANDLED
                return constructorHandle(cls, node)
            }
        }
        val refc = args.getOrNull(0) as? Class<*> ?: return NOT_HANDLED
        val internal = interpretedName(refc) ?: return NOT_HANDLED
        val cls = vm.resolve(internal) ?: return NOT_HANDLED
        return when (name) {
            "findStatic", "findVirtual", "findSpecial" -> {
                val member = args[1] as String
                val desc = descriptorOf(args[2] as MethodType)
                val found = vm.findInHierarchy(cls, member, desc)
                when {
                    found != null -> methodHandle(found.first, found.second, virtual = name == "findVirtual", special = name == "findSpecial")
                    // Inherited from a real superclass (`toString` on a class that does not override it): the
                    // real method, which a peer of the interpreted instance answers.
                    name == "findVirtual" -> raising { HOST.findVirtual(vm.loadReal(vm.realSuperName(cls)), member, args[2] as MethodType) }
                    else -> throw VmException(NoSuchMethodException("no such method: ${internal.replace('/', '.')}.$member$desc"))
                }
            }
            "findConstructor" -> {
                val desc = descriptorOf(args[1] as MethodType)
                val node = cls.methods.firstOrNull { it.name == "<init>" && it.desc == desc }
                    ?: throw VmException(NoSuchMethodException("no such constructor: ${internal.replace('/', '.')}.<init>$desc"))
                constructorHandle(cls, node)
            }
            "findGetter", "findStaticGetter", "findSetter" -> fieldHandle(cls, args[1] as String, name)
            "findVarHandle", "findStaticVarHandle" -> fieldVarHandle(cls, args[1] as String, static = name == "findStaticVarHandle")
            "findStaticSetter" ->
                throw VmUnsupportedException("MethodHandles.Lookup.$name on ${internal.replace('/', '.')} is not supported in the interpreter")
            else -> NOT_HANDLED
        }
    }

    private fun methodHandle(declaring: VmClass, node: MethodNode, virtual: Boolean, special: Boolean = false): MethodHandle {
        val isStatic = node.access and Opcodes.ACC_STATIC != 0
        val target = when {
            isStatic -> Target { args -> view(declaring, node).invoke(null, args.asList()) }
            // A virtual handle dispatches on the receiver it is given, as `invokevirtual` would.
            virtual && !special -> Target { args ->
                val receiver = args[0] ?: throw NullPointerException()
                val actual = ((receiver as? VmPeer)?.vmObject() as? VmObject)?.vmClass
                val (owner, method) = actual?.let { vm.findInHierarchy(it, node.name, node.desc) } ?: (declaring to node)
                view(owner, method).invoke(receiver, args.asList().subList(1, args.size))
            }
            else -> Target { args ->
                view(declaring, node).invoke(args[0] ?: throw NullPointerException(), args.asList().subList(1, args.size))
            }
        }
        val params = Descriptors.paramTypes(node.desc).map(::erased)
        val type = MethodType.methodType(erased(Descriptors.returnType(node.desc)), if (isStatic) params else listOf(Any::class.java) + params)
        return bound(target, type)
    }

    private fun constructorHandle(declaring: VmClass, node: MethodNode): MethodHandle {
        val target = Target { args -> view(declaring, node).invoke(null, args.asList()) }
        return bound(target, MethodType.methodType(Any::class.java, Descriptors.paramTypes(node.desc).map(::erased)))
    }

    private fun fieldHandle(cls: VmClass, field: String, kind: String): MethodHandle {
        val fqn = cls.name.replace('/', '.')
        if (kind == "findStaticGetter") {
            val desc = cls.staticFieldDescs[field] ?: throw VmException(NoSuchFieldException("$fqn.$field"))
            return bound(Target { vm.interpretedStaticValue(fqn, field) }, MethodType.methodType(erased(desc)))
        }
        val desc = instanceFieldDescriptor(cls, field) ?: throw VmException(NoSuchFieldException("$fqn.$field"))
        return if (kind == "findGetter") {
            bound(Target { args -> vm.interpretedFieldValue(args[0] ?: throw NullPointerException(), field) },
                MethodType.methodType(erased(desc), Any::class.java))
        } else {
            bound(Target { args -> vm.setInterpretedFieldValue(args[0] ?: throw NullPointerException(), field, args[1]); null },
                MethodType.methodType(Void.TYPE, Any::class.java, erased(desc)))
        }
    }

    /** A var handle over field [field] of [cls] (or of the interpreted supertype or interface declaring it). */
    private fun fieldVarHandle(cls: VmClass, field: String, static: Boolean): VmVarHandle {
        val fqn = cls.name.replace('/', '.')
        if (static) {
            val holder = staticHolder(cls, field) ?: throw VmException(NoSuchFieldException("$fqn.$field"))
            vm.ensureInitialized(holder)
            return VmVarHandle(
                vm, holder.staticFieldDescs.getValue(field), VmVarHandle.Kind.STATIC,
                cell = holder.staticCells.getValue(field), description = "static $fqn.$field",
            )
        }
        val desc = instanceFieldDescriptor(cls, field) ?: throw VmException(NoSuchFieldException("$fqn.$field"))
        return VmVarHandle(
            vm, desc, VmVarHandle.Kind.INSTANCE, field = field,
            volatile = vm.isVolatileInstanceField(cls, field), description = "$fqn.$field",
        )
    }

    private fun fieldUpdater(owner: String, args: List<Any?>): Any? {
        val cls = (args.getOrNull(0) as? Class<*>)?.let(::interpretedName)?.let(vm::resolve) ?: return NOT_HANDLED
        val field = args.lastOrNull() as? String ?: return NOT_HANDLED
        val type = when (owner.substringAfterLast('/')) {
            "AtomicIntegerFieldUpdater" -> 'I'
            "AtomicLongFieldUpdater" -> 'J'
            "AtomicReferenceFieldUpdater" -> 'L'
            else -> return NOT_HANDLED
        }
        val fqn = cls.name.replace('/', '.')
        // The real factories report a missing field as a RuntimeException around the NoSuchFieldException.
        if (instanceFieldDescriptor(cls, field) == null) throw VmException(RuntimeException(NoSuchFieldException(field)))
        return VmFieldUpdater(vm, fieldVarHandle(cls, field, static = false), type, "$fqn.$field")
    }

    private fun staticHolder(start: VmClass, name: String): VmClass? {
        var c: VmClass? = start
        while (c != null) {
            if (name in c.staticFieldDescs) return c
            for (iface in c.interfaces) vm.resolve(iface)?.let { staticHolder(it, name) }?.let { return it }
            c = c.superName?.let { vm.resolve(it) }
        }
        return null
    }

    private fun arrayVarHandle(arrayClass: Class<*>): VmVarHandle =
        VmVarHandle(vm, descriptorOf(arrayClass.componentType), VmVarHandle.Kind.ARRAY, description = arrayClass.typeName)

    private fun instanceFieldDescriptor(start: VmClass, name: String): String? {
        var c: VmClass? = start
        while (c != null) {
            c.instanceFieldDescs[name]?.let { return it }
            c = c.superName?.let { vm.resolve(it) }
        }
        return null
    }

    private fun view(declaring: VmClass, node: MethodNode) = VmMethodView(vm, declaring, node)

    /** A real handle of [type] that runs [target] with its arguments collected into an array. */
    private fun bound(target: Target, type: MethodType): MethodHandle =
        CALL.bindTo(target).asCollector(Array<Any?>::class.java, type.parameterCount()).asType(type)

    /** The real class a value of descriptor [desc] has when it crosses to real code: an interpreted class (or an
     *  array of one) has none of its own, so `Object`. */
    private fun erased(desc: String): Class<*> = when (desc) {
        "V" -> Void.TYPE
        "Z" -> java.lang.Boolean.TYPE
        "B" -> java.lang.Byte.TYPE
        "C" -> Character.TYPE
        "S" -> java.lang.Short.TYPE
        "I" -> Integer.TYPE
        "J" -> java.lang.Long.TYPE
        "F" -> java.lang.Float.TYPE
        "D" -> java.lang.Double.TYPE
        else -> {
            val element = desc.trimStart('[')
            val interpreted = element.startsWith("L") && vm.resolve(element.substring(1, element.length - 1)) != null
            if (interpreted) Any::class.java
            else runCatching { vm.loadReal(if (desc.startsWith("[")) desc else desc.substring(1, desc.length - 1)) }
                .getOrDefault(Any::class.java)
        }
    }

    /** [type] as the descriptor the interpreted member was compiled with: a stand-in class names its
     *  interpreted class. */
    private fun descriptorOf(type: MethodType): String =
        "(" + type.parameterList().joinToString("") { descriptorOf(it) } + ")" + descriptorOf(type.returnType())

    private fun descriptorOf(cls: Class<*>): String = when {
        cls.isArray -> "[" + descriptorOf(cls.componentType)
        cls.isPrimitive -> Type.getDescriptor(cls)
        else -> "L" + (interpretedName(cls) ?: cls.name.replace('.', '/')) + ";"
    }

    private fun interpretedName(cls: Class<*>): String? = vm.peerFactory.interpretedNameOf(cls)

    /** Runs a real `java.lang.invoke` operation for interpreted code: what it throws is the program's to catch
     *  (a `NoSuchMethodException` from a probe, whatever the target threw), not a VM failure. */
    private inline fun <T> raising(action: () -> T): T =
        try {
            action()
        } catch (e: VmException) {
            throw e
        } catch (e: VmUnsupportedException) {
            throw e
        } catch (e: VmInterruptedException) {
            throw e
        } catch (e: Throwable) {
            throw VmException(e)
        }

    /** What a bound handle runs. Public so the handle found on it links from any host. */
    fun interface Target {
        fun call(args: Array<Any?>): Any?
    }

    companion object {
        val NOT_HANDLED: Any = InterpretedReflection.NOT_HANDLED

        /** Taken HERE, by a direct call from a class of the VM, so the caller the platform sees is one it accepts. */
        private val HOST: MethodHandles.Lookup = MethodHandles.lookup()

        private val CALL: MethodHandle = HOST.findVirtual(
            Target::class.java, "call", MethodType.methodType(Any::class.java, Array<Any?>::class.java),
        )
    }
}
