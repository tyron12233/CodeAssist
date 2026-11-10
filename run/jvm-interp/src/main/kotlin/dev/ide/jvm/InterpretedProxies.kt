package dev.ide.jvm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `java.lang.reflect.Proxy` over INTERPRETED interfaces, as interpreted code uses it.
 *
 * The class literal of an interpreted interface is a constructor-only stand-in class ([PeerFactory.reflectionClass]),
 * so the real `Proxy.newProxyInstance` rejects it ("... is not an interface"). That is the usual way a test fakes
 * an interface it does not want to implement (a plugin test faking `PluginRegistration`), so when at least one
 * requested interface is interpreted the VM makes the proxy itself, the way the JDK does: it generates a class
 * implementing the interfaces whose every method, `Object`'s `equals`/`hashCode`/`toString` included, calls the
 * [java.lang.reflect.InvocationHandler] with the [Method] being called and the boxed arguments, then unboxes the
 * result. A checked exception the method does not declare surfaces as
 * [java.lang.reflect.UndeclaredThrowableException], as it does from a real proxy.
 *
 * The class is interpreted (defined with [Vm.defineSynthetic]), so platform code that receives the proxy sees its
 * peer, and the [Method]s the handler receives are the ones [InterpretedReflection] hands out for the interface,
 * so `method.name`, `method.invoke(delegate, args)` and `method.declaringClass` behave as they do on a JVM.
 * A proxy over real interfaces only is still the platform's own.
 */
internal class InterpretedProxies(private val vm: Vm, private val reflection: InterpretedReflection) {

    companion object {
        const val PACKAGE = "dev/ide/jvm/proxy/"
        private const val HANDLER = "Ljava/lang/reflect/InvocationHandler;"
        private const val METHOD = "Ljava/lang/reflect/Method;"
    }

    /** One method the proxy implements: its interpreted [name]/[desc], the [method] the handler is given, and
     *  the checked exceptions it may pass through unwrapped. */
    private class ProxyMethod(val name: String, val desc: String, val method: Method, val exceptions: List<String>)

    private val classes = ConcurrentHashMap<List<String>, VmClass>()
    private val counter = AtomicInteger()

    /** `Proxy.newProxyInstance(loader, interfaces, handler)`, or [InterpretedReflection.NOT_HANDLED] when no
     *  interface is interpreted. [interfaces] and [handler] are real values (an interpreted handler as its peer). */
    fun newProxyInstance(interfaces: Array<*>?, handler: Any?): Any? {
        val types = (interfaces ?: throw VmException(NullPointerException("interfaces"))).map {
            it as? Class<*> ?: throw VmException(NullPointerException("interface"))
        }
        if (types.none { vm.peerFactory.interpretedNameOf(it) != null }) return InterpretedReflection.NOT_HANDLED
        if (handler == null) throw VmException(NullPointerException("handler"))
        val names = types.map { c ->
            val interpreted = vm.peerFactory.interpretedNameOf(c)
            val isInterface = if (interpreted != null) vm.resolve(interpreted)?.let { it.access and Opcodes.ACC_INTERFACE != 0 } == true
                else c.isInterface
            if (!isInterface) throw VmException(IllegalArgumentException("${interpreted?.replace('/', '.') ?: c.name} is not an interface"))
            interpreted ?: Type.getInternalName(c)
        }
        if (names.toSet().size != names.size) {
            throw VmException(IllegalArgumentException("repeated interface: ${names.groupBy { it }.filter { it.value.size > 1 }.keys.first().replace('/', '.')}"))
        }
        val proxy = vm.newInstance(proxyClass(names))
        proxy.fields["h"] = Marshalling.realToVm(handler)
        return proxy
    }

    /** `Proxy.getInvocationHandler(proxy)` for a proxy made here, else NOT_HANDLED. */
    fun invocationHandlerOf(proxy: Any?): Any? {
        val o = Marshalling.realToVm(proxy) as? VmObject ?: return InterpretedReflection.NOT_HANDLED
        return if (isProxyClassName(o.vmClass.name)) o.fields["h"] else InterpretedReflection.NOT_HANDLED
    }

    fun isProxyClassName(internal: String): Boolean = internal.startsWith(PACKAGE)

    private fun proxyClass(interfaces: List<String>): VmClass = classes[interfaces] ?: synchronized(classes) {
        classes[interfaces] ?: run {
            val methods = methodsOf(interfaces)
            val name = PACKAGE + "\$Proxy" + counter.incrementAndGet()
            val cls = vm.defineSynthetic(classNode(name, interfaces, methods))
            methods.forEachIndexed { i, m -> cls.statics["m$i"] = m.method }
            vm.ensureInitialized(cls)
            cls.also { classes[interfaces] = it }
        }
    }

    /** `Object`'s three methods, then every instance method of [interfaces] and their superinterfaces, the first
     *  declaration of a signature winning, as the JDK's proxy picks them. */
    private fun methodsOf(interfaces: List<String>): List<ProxyMethod> {
        val out = LinkedHashMap<String, ProxyMethod>()
        for ((n, d) in listOf("hashCode" to "()I", "equals" to "(Ljava/lang/Object;)Z", "toString" to "()Ljava/lang/String;")) {
            val real = Any::class.java.getMethod(n, *Type.getArgumentTypes(d).map { Any::class.java }.toTypedArray())
            out[n + d] = ProxyMethod(n, d, real, emptyList())
        }
        val seen = HashSet<String>()
        fun visit(internal: String) {
            if (!seen.add(internal)) return
            val vc = vm.resolve(internal)
            if (vc == null) {
                val real = runCatching { vm.loadReal(internal) }.getOrNull() ?: return
                for (m in real.methods) {
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    val desc = Type.getMethodDescriptor(m)
                    out.putIfAbsent(m.name + desc, ProxyMethod(m.name, desc, m, m.exceptionTypes.map { Type.getInternalName(it) }))
                }
                return
            }
            for ((m, node) in reflection.declaredMethods(vc)) {
                if (node.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) != 0) continue
                out.putIfAbsent(node.name + node.desc, ProxyMethod(node.name, node.desc, m, node.exceptions.orEmpty()))
            }
            vc.interfaces.forEach(::visit)
        }
        interfaces.forEach(::visit)
        return out.values.toList()
    }

    /** `final class <name> implements <interfaces>`: a handler field, a [Method] static per method, and each
     *  method boxing its arguments into `h.invoke(this, m<i>, args)` and unboxing what comes back. */
    private fun classNode(name: String, interfaces: List<String>, methods: List<ProxyMethod>): ClassNode {
        val cn = ClassNode()
        cn.version = Opcodes.V1_8
        cn.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        cn.name = name
        cn.superName = "java/lang/Object"
        cn.interfaces = interfaces.toMutableList()
        cn.fields.add(FieldNode(Opcodes.ACC_PRIVATE, "h", HANDLER, null, null))
        cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1; maxLocals = 1
        })
        methods.forEachIndexed { i, m ->
            cn.fields.add(FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "m$i", METHOD, null, null))
            cn.methods.add(method(name, i, m))
        }
        return cn
    }

    private fun method(owner: String, index: Int, m: ProxyMethod): MethodNode {
        val node = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, m.name, m.desc, null, m.exceptions.toTypedArray())
        val code = node.instructions
        val params = Type.getArgumentTypes(m.desc)
        val ret = Type.getReturnType(m.desc)
        val start = LabelNode()
        val end = LabelNode()
        val rethrow = LabelNode()
        val wrap = LabelNode()

        code.add(start)
        code.add(VarInsnNode(Opcodes.ALOAD, 0))
        code.add(FieldInsnNode(Opcodes.GETFIELD, owner, "h", HANDLER))
        code.add(VarInsnNode(Opcodes.ALOAD, 0))
        code.add(FieldInsnNode(Opcodes.GETSTATIC, owner, "m$index", METHOD))
        var slot = 1
        if (params.isEmpty()) {
            code.add(InsnNode(Opcodes.ACONST_NULL)) // a real proxy passes null, not an empty array, for no arguments
        } else {
            code.add(LdcInsnNode(params.size))
            code.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
            params.forEachIndexed { j, t ->
                code.add(InsnNode(Opcodes.DUP))
                code.add(LdcInsnNode(j))
                code.add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot))
                box(code, t)
                code.add(InsnNode(Opcodes.AASTORE))
                slot += t.size
            }
        }
        code.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/reflect/InvocationHandler", "invoke",
            "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", true))
        unboxAndReturn(code, ret)
        code.add(end)

        // RuntimeException, Error and the declared exceptions pass through; any other throwable is wrapped.
        code.add(rethrow)
        code.add(InsnNode(Opcodes.ATHROW))
        code.add(wrap)
        code.add(VarInsnNode(Opcodes.ASTORE, slot))
        code.add(TypeInsnNode(Opcodes.NEW, "java/lang/reflect/UndeclaredThrowableException"))
        code.add(InsnNode(Opcodes.DUP))
        code.add(VarInsnNode(Opcodes.ALOAD, slot))
        code.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/reflect/UndeclaredThrowableException", "<init>",
            "(Ljava/lang/Throwable;)V", false))
        code.add(InsnNode(Opcodes.ATHROW))
        for (type in listOf("java/lang/RuntimeException", "java/lang/Error") + m.exceptions) {
            node.tryCatchBlocks.add(TryCatchBlockNode(start, end, rethrow, type))
        }
        node.tryCatchBlocks.add(TryCatchBlockNode(start, end, wrap, "java/lang/Throwable"))
        node.maxStack = 8
        node.maxLocals = slot + 1
        return node
    }

    private fun box(code: InsnList, t: Type) {
        val wrapper = wrapperOf(t) ?: return
        code.add(MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", "(${t.descriptor})L$wrapper;", false))
    }

    private fun unboxAndReturn(code: InsnList, ret: Type) {
        when (ret.sort) {
            Type.VOID -> {
                code.add(InsnNode(Opcodes.POP))
                code.add(InsnNode(Opcodes.RETURN))
            }
            Type.OBJECT, Type.ARRAY -> {
                if (ret.internalName != "java/lang/Object") code.add(TypeInsnNode(Opcodes.CHECKCAST, ret.internalName))
                code.add(InsnNode(Opcodes.ARETURN))
            }
            else -> {
                val wrapper = wrapperOf(ret)!!
                // A null for a primitive result is a NullPointerException here, as from a real proxy.
                code.add(TypeInsnNode(Opcodes.CHECKCAST, wrapper))
                code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, "${ret.className}Value", "()${ret.descriptor}", false))
                code.add(InsnNode(ret.getOpcode(Opcodes.IRETURN)))
            }
        }
    }

    private fun wrapperOf(t: Type): String? = when (t.sort) {
        Type.BOOLEAN -> "java/lang/Boolean"
        Type.BYTE -> "java/lang/Byte"
        Type.CHAR -> "java/lang/Character"
        Type.SHORT -> "java/lang/Short"
        Type.INT -> "java/lang/Integer"
        Type.LONG -> "java/lang/Long"
        Type.FLOAT -> "java/lang/Float"
        Type.DOUBLE -> "java/lang/Double"
        else -> null
    }
}
